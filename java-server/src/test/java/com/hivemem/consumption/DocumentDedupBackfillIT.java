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
    private static final String VEC_B = unitVector(1);

    private static String unitVector(int hotIndex) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 384; i++) {
            if (i > 0) sb.append(',');
            sb.append(i == hotIndex ? '1' : '0');
        }
        return sb.append(']').toString();
    }

    private UUID seedCell(String content, String embedding, String source, OffsetDateTime createdAt) {
        return seedCell(content, embedding, source, "committed", createdAt);
    }

    private UUID seedCell(String content, String embedding, String source, String status,
                          OffsetDateTime createdAt) {
        UUID id = UUID.randomUUID();
        dsl.execute("INSERT INTO cells (id, content, embedding, source, status, created_at, valid_from) "
                + "VALUES (?, ?, ?::vector, ?, ?, ?::timestamptz, now())",
                id, content, embedding, source, status, createdAt);
        return id;
    }

    /** Reproduces the pre-fix production state: a soft-deleted duplicate with a duplicate_of
     *  tunnel to its original, but its facts left untouched (as if settled by the old code). */
    private void softDeleteWithTunnel(UUID duplicate, UUID original) {
        dsl.execute("INSERT INTO tunnels (from_cell, to_cell, relation, note, status, created_by) "
                + "VALUES (?, ?, 'duplicate_of', 'test', 'committed', 'test')", duplicate, original);
        dsl.execute("UPDATE cells SET valid_until = now() WHERE id = ?", duplicate);
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
        UUID original = seedCell("Rechnung 4711 Betrag 199", VEC_A, "consumption:a", t0);
        UUID dup = seedCell("Rechnung 4711 Betrag 199", VEC_A, "consumption:b", t0.plusMinutes(5));

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
        UUID a = seedCell("Rechnung 3001 Betrag 199", VEC_A, "consumption:a", t0);
        UUID b = seedCell("Mietvertrag 3002 Kaution 900", VEC_A, "consumption:b", t0.plusMinutes(1));
        UUID c = seedCell("Kontoauszug 3003 Buchungen 12", VEC_A, "consumption:c", t0.plusMinutes(2));

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
        UUID rejected = seedCell("Rechnung 4712 Betrag 199", VEC_A, "consumption:a", "rejected", t0);
        UUID original = seedCell("Rechnung 4712 Betrag 199", VEC_A, "consumption:b", "committed",
                t0.plusMinutes(1));
        UUID dup = seedCell("Rechnung 4712 Betrag 199", VEC_A, "consumption:c", "committed",
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
        UUID original = seedCell("Rechnung 4713 Betrag 199", VEC_A, "consumption:a", t0);
        UUID dup = seedCell("Rechnung 4713 Betrag 199", VEC_A, "consumption:b", "pending",
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

    /**
     * Resumable backfill for the orphans left behind by discards that pre-date the fix: a
     * soft-deleted duplicate with a live {@code duplicate_of} tunnel but its facts untouched.
     * Covers both branches of {@code reassignOrInvalidateFacts} and confirms the walk is
     * idempotent once every orphan is settled.
     */
    @Test
    void backfillSettlesBothBranchesAndIsIdempotent() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        DocumentDedupService service = newService(repo);
        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-23T10:00:00Z");

        // branch A: original already has facts -> the duplicate's fact is invalidated
        UUID origA = seedCell("a", VEC_A, "consumption:a1", "committed", t0.minusDays(3));
        UUID dupA = seedCell("a", VEC_A, "consumption:a2", "committed", t0.minusDays(2));
        UUID keptA = seedFact(origA, "SYNTHETIC INSURER", "policy_number", "1000000001");
        UUID dropA = seedFact(dupA, "SYNTHETIC INSURER", "policy_number", "1000000001");
        softDeleteWithTunnel(dupA, origA);

        // branch B: original has none -> the duplicate's fact is repointed
        UUID origB = seedCell("b", VEC_B, "consumption:b1", "committed", t0.minusDays(3));
        UUID dupB = seedCell("b", VEC_B, "consumption:b2", "committed", t0.minusDays(1));
        UUID moveB = seedFact(dupB, "SYNTHETIC UTILITY", "customer_number", "1000000002");
        softDeleteWithTunnel(dupB, origB);

        var first = service.factOrphanBackfill(null, null, 100);

        assertEquals(2, first.checked());
        assertEquals(1, first.invalidated());
        assertEquals(1, first.repointed());
        assertFalse(factIsLive(dropA));
        assertTrue(factIsLive(keptA));
        assertTrue(factIsLive(moveB));
        assertEquals(origB, factSource(moveB));

        var second = service.factOrphanBackfill(null, null, 100);
        assertEquals(0, second.checked(), "a second run must find nothing left to do");
    }

    /**
     * A discarded cell whose {@code duplicate_of} original is itself discarded with no {@code
     * parent_id} successor: {@code resolveLiveFactTarget} resolves nothing, so the fact is left
     * untouched and the cell is reported {@code skipped}. Unlike the settled branches, a skipped
     * cell does NOT drop out of the walk's selection predicate — it still has a live fact of its
     * own — so a second run from a null cursor must see it again, not treat it as done.
     */
    @Test
    void backfillSkipsAndReVisitsACellWithNoLiveFactTarget() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        DocumentDedupService service = newService(repo);
        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-24T10:00:00Z");

        UUID deadOriginal = seedCell("c", VEC_A, "consumption:c1", "committed", t0.minusDays(3));
        UUID dup = seedCell("c", VEC_A, "consumption:c2", "committed", t0.minusDays(1));
        UUID stuckFact = seedFact(dup, "SYNTHETIC INSURER", "policy_number", "1000000003");
        softDeleteWithTunnel(dup, deadOriginal);
        // The original itself is discarded, and has no parent_id child to succeed it.
        dsl.execute("UPDATE cells SET valid_until = now() WHERE id = ?", deadOriginal);

        var first = service.factOrphanBackfill(null, null, 100);
        assertEquals(1, first.checked());
        assertEquals(0, first.invalidated());
        assertEquals(0, first.repointed());
        assertEquals(1, first.skipped());
        assertEquals(0, first.failed());
        assertEquals(dup, first.lastId(), "the walk must advance past the unresolved cell, not loop on it");
        assertTrue(factIsLive(stuckFact), "no live target -> the fact must not be touched at all");

        var second = service.factOrphanBackfill(null, null, 100);
        assertEquals(1, second.checked(),
                "a skipped cell is still an unsettled orphan and must be revisited from a null cursor");
        assertEquals(1, second.skipped());
    }

    /**
     * Cursor-mechanics regression for {@code factOrphanBackfill}, mirroring
     * {@code backfillCursorAdvancesBeyondTheFirstPageWithoutAnyDeletions} above: with a limit
     * smaller than the number of orphans, the walk must reach every one of them across successive
     * calls rather than repeating (or skipping) a page.
     */
    @Test
    void backfillCursorAdvancesAcrossPagesForFactOrphans() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        DocumentDedupService service = newService(repo);
        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-25T10:00:00Z");

        UUID origA = seedCell("d", VEC_A, "consumption:d1", "committed", t0.minusDays(5));
        UUID dupA = seedCell("d", VEC_A, "consumption:d2", "committed", t0.minusDays(4));
        seedFact(origA, "SYNTHETIC INSURER", "policy_number", "1000000004");
        UUID dropA = seedFact(dupA, "SYNTHETIC INSURER", "policy_number", "1000000004");
        softDeleteWithTunnel(dupA, origA);

        UUID origB = seedCell("e", VEC_B, "consumption:e1", "committed", t0.minusDays(3));
        UUID dupB = seedCell("e", VEC_B, "consumption:e2", "committed", t0.minusDays(2));
        UUID moveB = seedFact(dupB, "SYNTHETIC UTILITY", "customer_number", "1000000005");
        softDeleteWithTunnel(dupB, origB);

        UUID origC = seedCell("f", VEC_A, "consumption:f1", "committed", t0.minusDays(1));
        UUID dupC = seedCell("f", VEC_A, "consumption:f2", "committed", t0);
        UUID moveC = seedFact(dupC, "SYNTHETIC UTILITY", "meter_number", "1000000006");
        softDeleteWithTunnel(dupC, origC);

        var p1 = service.factOrphanBackfill(null, null, 1);
        assertEquals(1, p1.checked());
        assertEquals(dupA, p1.lastId());
        assertEquals(2, p1.remaining());

        var p2 = service.factOrphanBackfill(p1.lastCreatedAt(), p1.lastId(), 1);
        assertEquals(dupB, p2.lastId());
        assertEquals(1, p2.remaining());

        var p3 = service.factOrphanBackfill(p2.lastCreatedAt(), p2.lastId(), 1);
        assertEquals(dupC, p3.lastId());
        assertEquals(0, p3.remaining());

        var p4 = service.factOrphanBackfill(p3.lastCreatedAt(), p3.lastId(), 1);
        assertEquals(0, p4.checked(), "walk is exhausted");
        assertEquals(0, p4.remaining());
        assertEquals(dupC, p4.lastId(), "an empty page returns the cursor it was given");

        assertFalse(factIsLive(dropA), "page 1's orphan must have been settled (invalidated)");
        assertTrue(factIsLive(moveB), "page 2's orphan must have been settled (repointed)");
        assertTrue(factIsLive(moveC), "page 3's orphan must have been settled (repointed)");
    }
}
