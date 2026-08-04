package com.hivemem.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hivemem.attachment.AttachmentRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentDedupBackfillIT extends ConsumptionITSupport {

    /**
     * dedupBackfill() uses DocumentDedupRepository.findSimilarOlderCandidates, which casts
     * embedding to vector(dim) with dim derived dynamically per-call from the target cell's own
     * embedding — any fixed-size vector works; 384 here just exercises a realistic dimension.
     */
    private static final String VEC_A = unitVector(0);

    private static String unitVector(int hotIndex) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 384; i++) {
            if (i > 0) sb.append(',');
            sb.append(i == hotIndex ? '1' : '0');
        }
        return sb.append(']').toString();
    }

    private UUID seed(String content, String embedding, String source, OffsetDateTime createdAt) {
        return seed(content, embedding, source, "committed", createdAt);
    }

    private UUID seed(String content, String embedding, String source, String status,
                      OffsetDateTime createdAt) {
        UUID id = UUID.randomUUID();
        dsl.execute("INSERT INTO cells (id, content, embedding, source, status, created_at, valid_from) "
                + "VALUES (?, ?, ?::vector, ?, ?, ?::timestamptz, now())",
                id, content, embedding, source, status, createdAt);
        return id;
    }

    private DocumentDedupService newService(DocumentDedupRepository repo) {
        return new DocumentDedupService(
                repo, new AttachmentRepository(dsl), seaweed, new DedupProperties());
    }

    @Test
    void backfillDiscardsNewerDuplicateAndKeepsOldest() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        DocumentDedupService service = newService(repo);

        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-10T10:00:00Z");
        UUID original = seed("Rechnung 4711 Betrag 199", VEC_A, "consumption:a", t0);
        UUID dup = seed("Rechnung 4711 Betrag 199", VEC_A, "consumption:b", t0.plusMinutes(5));

        DocumentDedupService.BackfillReport report = service.dedupBackfill(null, null, 500);

        assertEquals(2, report.checked());
        assertEquals(1, report.discarded());
        assertEquals(0, report.remaining());
        assertEquals(dup, report.lastId(), "cursor ends on the last cell the walk looked at");
        assertTrue(repo.findTarget(original).isPresent(), "oldest original is kept");
        assertFalse(repo.findTarget(dup).isPresent(), "newer duplicate is discarded");
    }

    /**
     * The regression a bare {@code limit} would cause: none of these three cells is a duplicate, so
     * none gets soft-deleted, so a limit-only query would hand back the same first page forever
     * while cheerfully reporting progress. Walking the returned cursor must reach every cell.
     */
    @Test
    void backfillCursorAdvancesBeyondTheFirstPageWithoutAnyDeletions() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        DocumentDedupService service = newService(repo);

        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-20T10:00:00Z");
        UUID a = seed("Rechnung 3001 Betrag 199", VEC_A, "consumption:a", t0);
        UUID b = seed("Mietvertrag 3002 Kaution 900", VEC_A, "consumption:b", t0.plusMinutes(1));
        UUID c = seed("Kontoauszug 3003 Buchungen 12", VEC_A, "consumption:c", t0.plusMinutes(2));

        DocumentDedupService.BackfillReport p1 = service.dedupBackfill(null, null, 1);
        assertEquals(1, p1.checked());
        assertEquals(0, p1.discarded());
        assertEquals(a, p1.lastId());
        assertEquals(2, p1.remaining());

        DocumentDedupService.BackfillReport p2 =
                service.dedupBackfill(p1.lastCreatedAt(), p1.lastId(), 1);
        assertEquals(b, p2.lastId());
        assertEquals(1, p2.remaining());

        DocumentDedupService.BackfillReport p3 =
                service.dedupBackfill(p2.lastCreatedAt(), p2.lastId(), 1);
        assertEquals(c, p3.lastId());
        assertEquals(0, p3.remaining());

        DocumentDedupService.BackfillReport p4 =
                service.dedupBackfill(p3.lastCreatedAt(), p3.lastId(), 1);
        assertEquals(0, p4.checked(), "walk is exhausted");
        assertEquals(0, p4.remaining());
        assertEquals(c, p4.lastId(), "an empty page returns the cursor it was given");
    }

    /**
     * A mixed committed+rejected group: the rejected member is neither target nor candidate, and a
     * second pass over the same store discards nothing.
     */
    @Test
    void backfillLeavesRejectedCellsAloneAndIsIdempotent() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        DocumentDedupService service = newService(repo);

        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-21T10:00:00Z");
        UUID rejected = seed("Rechnung 4712 Betrag 199", VEC_A, "consumption:a", "rejected", t0);
        UUID original = seed("Rechnung 4712 Betrag 199", VEC_A, "consumption:b", "committed",
                t0.plusMinutes(1));
        UUID dup = seed("Rechnung 4712 Betrag 199", VEC_A, "consumption:c", "committed",
                t0.plusMinutes(2));

        DocumentDedupService.BackfillReport first = service.dedupBackfill(null, null, 500);
        assertEquals(2, first.checked(), "the rejected cell is not even visited");
        assertEquals(1, first.discarded());
        assertTrue(repo.findTarget(original).isPresent());
        assertFalse(repo.findTarget(dup).isPresent());
        assertEquals("rejected", dsl.fetchOne("SELECT status FROM cells WHERE id = ?", rejected)
                .get("status", String.class));
        assertNull(dsl.fetchOne("SELECT valid_until FROM cells WHERE id = ?", rejected)
                .get("valid_until", OffsetDateTime.class), "the rejected cell stays untouched");

        DocumentDedupService.BackfillReport second = service.dedupBackfill(null, null, 500);
        assertEquals(1, second.checked());
        assertEquals(0, second.discarded(), "a second pass discards nothing");
    }

    /** A pending re-scan is discarded like any other duplicate — and becomes rejected. */
    @Test
    void backfillDiscardsAPendingRescanAndTakesItOutOfTheApprovalQueue() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        DocumentDedupService service = newService(repo);

        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-22T10:00:00Z");
        UUID original = seed("Rechnung 4713 Betrag 199", VEC_A, "consumption:a", t0);
        UUID dup = seed("Rechnung 4713 Betrag 199", VEC_A, "consumption:b", "pending",
                t0.plusMinutes(5));

        DocumentDedupService.BackfillReport report = service.dedupBackfill(null, null, 500);

        assertEquals(2, report.checked());
        assertEquals(1, report.discarded());
        assertTrue(repo.findTarget(original).isPresent());
        assertFalse(repo.findTarget(dup).isPresent());
        assertEquals("rejected", dsl.fetchOne("SELECT status FROM cells WHERE id = ?", dup)
                .get("status", String.class));
        assertEquals(0L, dsl.fetchOne("SELECT count(*) AS n FROM pending_approvals WHERE id = ?", dup)
                .get("n", Long.class), "the discarded pending cell leaves the approval queue");
    }
}
