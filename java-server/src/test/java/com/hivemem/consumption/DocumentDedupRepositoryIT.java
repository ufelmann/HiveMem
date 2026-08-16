package com.hivemem.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hivemem.write.WriteToolRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class DocumentDedupRepositoryIT extends ConsumptionITSupport {

    /**
     * findSimilarOlderCandidates casts embedding to vector(dim), where dim is derived at query
     * time from the target cell's own embedding (vector_dims) rather than hardcoded — so any
     * fixed-size vector works here. VEC_A/VEC_B are 384-dimensional unit vectors on two different
     * axes (cosine distance 1.0 apart) simply to exercise a realistic, larger dimension.
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

    private UUID seedCell(String content, String embedding, String source,
                          String status, OffsetDateTime createdAt) {
        return seedCellWithId(UUID.randomUUID(), content, embedding, source, status, createdAt);
    }

    /** For the few cases where the id itself is under test and must not be random. */
    private UUID seedCellWithId(UUID id, String content, String embedding, String source,
                                String status, OffsetDateTime createdAt) {
        dsl.execute(
                "INSERT INTO cells (id, content, embedding, source, status, created_at, valid_from) "
                + "VALUES (?, ?, ?::vector, ?, ?, ?::timestamptz, now())",
                id, content, embedding, source, status, createdAt);
        return id;
    }

    /** A revision cell whose {@code parent_id} points at the cell it supersedes — the shape
     *  {@code resolveLiveFactTarget}'s successor walk follows. */
    private UUID seedCellWithParent(UUID parentId, String content, String embedding, String source,
                                    String status, OffsetDateTime createdAt) {
        UUID id = UUID.randomUUID();
        dsl.execute(
                "INSERT INTO cells (id, parent_id, content, embedding, source, status, created_at, valid_from) "
                + "VALUES (?, ?, ?, ?::vector, ?, ?, ?::timestamptz, now())",
                id, parentId, content, embedding, source, status, createdAt);
        return id;
    }

    private void linkAttachment(UUID cellId, UUID attachmentId) {
        dsl.execute(
                "INSERT INTO cell_attachments (cell_id, attachment_id, extraction_source) "
                + "VALUES (?, ?, true)", cellId, attachmentId);
    }

    private UUID seedFact(UUID sourceId, String subject, String predicate, String object) {
        UUID id = UUID.randomUUID();
        dsl.execute(
                "INSERT INTO facts (id, subject, predicate, object, source_id, status) "
                + "VALUES (?, ?, ?, ?, ?, 'committed')",
                id, subject, predicate, object, sourceId);
        return id;
    }

    private boolean factIsLive(UUID factId) {
        return dsl.fetchOne("SELECT valid_until FROM facts WHERE id = ?", factId)
                .get("valid_until") == null;
    }

    private UUID factSource(UUID factId) {
        return dsl.fetchOne("SELECT source_id FROM facts WHERE id = ?", factId)
                .get("source_id", UUID.class);
    }

    private String factSubject(UUID factId) {
        return dsl.fetchOne("SELECT subject FROM facts WHERE id = ?", factId)
                .get("subject", String.class);
    }

    @Test
    void findsOlderSimilarScanCandidate() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-01T10:00:00Z");
        UUID original = seedCell("Rechnung 4711", VEC_A, "consumption:a",
                "committed", t0);
        UUID dup = seedCell("Rechnung 4711", VEC_A, "consumption:b",
                "committed", t0.plusMinutes(5));

        List<DocumentDedupRepository.Candidate> cands =
                repo.findSimilarOlderCandidates(dup, 0.92, 10);

        assertEquals(1, cands.size());
        assertEquals(original, cands.get(0).id());
        assertTrue(cands.get(0).cosine() >= 0.99);
        assertEquals(t0, cands.get(0).createdAt());
    }

    @Test
    void ignoresNonScanAndNewerAndDissimilar() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-02T10:00:00Z");
        UUID dup = seedCell("Rechnung 4711", VEC_A, "consumption:b", "committed", t0);
        seedCell("Rechnung 4711", VEC_A, "manual:x", "committed", t0.minusMinutes(5)); // not a scan
        seedCell("Rechnung 4711", VEC_A, "consumption:c", "committed", t0.plusMinutes(5)); // newer
        seedCell("Mietvertrag", VEC_B, "consumption:d", "committed", t0.minusMinutes(5)); // dissimilar vec

        List<DocumentDedupRepository.Candidate> cands =
                repo.findSimilarOlderCandidates(dup, 0.92, 10);

        assertTrue(cands.isEmpty(), "expected no candidates, got " + cands);
    }

    @Test
    void softDeleteAndReferenceCount() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-03T10:00:00Z");
        UUID att = UUID.randomUUID();
        dsl.execute("INSERT INTO attachments (id, file_hash, mime_type, original_filename, "
                + "size_bytes, s3_key_original, uploaded_by) VALUES (?, ?, 'application/pdf', 'x.pdf', 1, ?, 'system')",
                att, "hash-" + att, "key-" + att);
        UUID original = seedCell("Rechnung 4711", VEC_A, "consumption:a", "committed", t0.minusMinutes(5));
        UUID dup = seedCell("Rechnung 4711", VEC_A, "consumption:b", "committed", t0);
        linkAttachment(dup, att);

        assertEquals(1, repo.countOtherLiveCellsForAttachment(att, UUID.randomUUID()));
        repo.linkAndSoftDelete(dup, original, "auto-dedup note", "system-dedup");
        // After soft-delete the dup is no longer "live".
        assertEquals(0, repo.countOtherLiveCellsForAttachment(att, dup));

        var keys = repo.findAttachmentKeysForCell(dup);
        assertTrue(keys.isPresent());
        assertEquals(att, keys.get().attachmentId());
        assertFalse(repo.findTarget(dup).isPresent(), "soft-deleted cell is not a valid target");
    }

    @Test
    void linkAndSoftDeleteWritesTunnelAndSoftDeletesAtomically() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-04T10:00:00Z");
        UUID original = seedCell("Rechnung 4711", VEC_A, "consumption:a", "committed", t0);
        UUID dup = seedCell("Rechnung 4711", VEC_A, "consumption:b", "committed", t0.plusMinutes(5));

        repo.linkAndSoftDelete(dup, original, "auto-dedup note", "system-dedup");

        assertFalse(repo.findTarget(dup).isPresent(), "duplicate must be soft-deleted");
        int tunnels = dsl.fetchOne(
                "SELECT count(*) AS n FROM tunnels "
                + "WHERE from_cell = ? AND to_cell = ? AND relation = 'duplicate_of'",
                dup, original).get("n", Long.class).intValue();
        assertEquals(1, tunnels, "exactly one duplicate_of tunnel must be written");
    }

    // ---------------------------------------------------------------------------------------
    // Lexical candidate channel
    //
    // All texts below are synthetic. They exist to give cells.tsv enough lexemes matching the
    // channel's ^[a-zäöüß]{6,}$ form filter, and to be near-identical between "original" and
    // "re-scan" so the caller's Jaccard gate would confirm them.
    // ---------------------------------------------------------------------------------------

    /** The OLDER cell of every pair below — and deliberately the SHORTER of the two. */
    private static final String DOC_ORIGINAL =
            "Zusatzvereinbarung zwischen der Beispielfirma Musterbau GmbH und dem Auftragnehmer "
            + "ueber die Erbringung von Beratungsleistungen im Geschaeftsjahr 2026. "
            + "Die Vertragsparteien vereinbaren eine monatliche Verguetung in Hoehe von "
            + "eintausendzweihundert Euro zuzueglich der gesetzlichen Umsatzsteuer. "
            + "Kuendigungsfrist drei Monate zum Quartalsende, Gerichtsstand ist Musterstadt.";

    /**
     * The re-scan: the NEWER cell, and the dedup target. It carries tokens the older cell does NOT
     * have, so the OLDER cell is a strict lexical SUBSET of the target — the asymmetric direction
     * that fails in production. A conjunctive {@code plainto_tsquery(targetContent)} ANDs every
     * target lexeme and therefore only matches candidates that are lexical SUPERSETS of the target;
     * against this pair it finds nothing. Only the OR-ed lexeme query recalls it, which is exactly
     * what these tests must pin down. Inverting the two texts would make the assertions pass under
     * a plainto_tsquery reimplementation and guard nothing.
     */
    private static final String DOC_RESCAN =
            DOC_ORIGINAL + " Nebenabreden beduerfen zwingend der Schriftform.";

    /** Shares vocabulary with the pair above but is a different document. */
    private static final String DOC_DIFFERENT =
            "Zusatzvereinbarung zwischen der Beispielfirma Musterbau GmbH und dem Auftragnehmer "
            + "ueber die Rueckgabe des Dienstfahrzeuges nach Beendigung des Arbeitsverhaeltnisses. "
            + "Das Fahrzeug ist gereinigt und vollgetankt am Betriebsgelaende abzustellen.";

    private void softDelete(UUID id) {
        dsl.execute("UPDATE cells SET valid_until = now() WHERE id = ?", id);
    }

    /** The pair from the field report: cosine far below recall, texts all but identical. */
    @Test
    void lexicalChannelFindsOlderTwinWhenVectorsAreFarApart() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-06T10:00:00Z");
        UUID original = seedCell(DOC_ORIGINAL, VEC_B, "consumption:a", "committed", t0);
        UUID rescan = seedCell(DOC_RESCAN, VEC_A, "consumption:b", "committed", t0.plusDays(1));

        List<DocumentDedupRepository.Candidate> cands =
                repo.findSimilarOlderCandidates(rescan, 0.92, 10);

        assertEquals(1, cands.size(), "expected the lexical channel to recall the twin, got " + cands);
        assertEquals(original, cands.get(0).id());
        assertEquals(DOC_ORIGINAL, cands.get(0).content());
        assertEquals(t0, cands.get(0).createdAt());

        // Proof that this pair really is the asymmetric direction, and therefore that the assertion
        // above is not satisfiable by the conjunctive query the spec rejects: plainto_tsquery ANDs
        // every lexeme of the target, and the older twin lacks some of them, so it matches nothing.
        // If this ever passes, the fixture has been inverted and the test guards nothing.
        long conjunctiveHits = dsl.fetchOne(
                "SELECT count(*) AS n FROM cells c "
                + "WHERE c.id = ? AND c.tsv @@ plainto_tsquery('simple', ?)",
                original, DOC_RESCAN).get("n", Long.class);
        assertEquals(0L, conjunctiveHits,
                "fixture is inverted: plainto_tsquery(target) still matches the older twin");
    }

    /** A lexical hit whose own embedding is missing must yield cosine = null, not an error. */
    @Test
    void lexicalCandidateWithoutEmbeddingYieldsNullCosine() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-07T10:00:00Z");
        UUID original = seedCell(DOC_ORIGINAL, null, "consumption:a", "committed", t0);
        UUID rescan = seedCell(DOC_RESCAN, VEC_A, "consumption:b", "committed", t0.plusDays(1));

        List<DocumentDedupRepository.Candidate> cands =
                repo.findSimilarOlderCandidates(rescan, 0.92, 10);

        assertEquals(1, cands.size(), "expected one lexical candidate, got " + cands);
        assertEquals(original, cands.get(0).id());
        assertNull(cands.get(0).cosine(), "no embedding -> cosine is 'not comparable', i.e. null");
    }

    /** The whole point of the channel: it must still run when the TARGET has no embedding at all. */
    @Test
    void lexicalChannelRunsWhenTargetHasNoEmbedding() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-08T10:00:00Z");
        UUID original = seedCell(DOC_ORIGINAL, VEC_A, "consumption:a", "committed", t0);
        UUID rescan = seedCell(DOC_RESCAN, null, "consumption:b", "committed", t0.plusDays(1));

        List<DocumentDedupRepository.Candidate> cands =
                repo.findSimilarOlderCandidates(rescan, 0.92, 10);

        assertEquals(1, cands.size(), "vector channel is empty, lexical must still deliver: " + cands);
        assertEquals(original, cands.get(0).id());
        assertNull(cands.get(0).cosine());
    }

    /**
     * Same guards as the vector channel: source, liveness, status, strictly older. Every row below
     * is identical in text to the target, so the ONLY reason to exclude one is the filter under
     * test. The qualifying row is the positive control: without it a channel that returns nothing
     * at all — or one that was never wired up — would satisfy this test vacuously.
     */
    @Test
    void lexicalChannelAppliesTheSameFiltersAsTheVectorChannel() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-09T10:00:00Z");
        UUID rescan = seedCell(DOC_RESCAN, VEC_A, "consumption:b", "committed", t0);
        seedCell(DOC_ORIGINAL, VEC_B, "manual:x", "committed", t0.minusDays(1));      // not a scan
        seedCell(DOC_ORIGINAL, VEC_B, "consumption:c", "committed", t0.plusDays(1));  // newer
        seedCell(DOC_ORIGINAL, VEC_B, "consumption:e", "rejected", t0.minusDays(1));  // rejected
        UUID deleted = seedCell(DOC_ORIGINAL, VEC_B, "consumption:f", "committed", t0.minusDays(1));
        softDelete(deleted);                                                          // not live
        // Positive controls: live, consumption-sourced, strictly older — one per accepted status.
        // 'pending' qualifies so that a not-yet-approved re-scan can never become the permanent
        // original of a group; only 'rejected' is excluded, because it is not archive content.
        UUID pending = seedCell(DOC_ORIGINAL, VEC_B, "consumption:d", "pending", t0.minusDays(2));
        UUID qualifying = seedCell(DOC_ORIGINAL, VEC_B, "consumption:g", "committed", t0.minusDays(1));

        List<DocumentDedupRepository.Candidate> cands =
                repo.findSimilarOlderCandidates(rescan, 0.92, 10);

        assertEquals(List.of(pending, qualifying), cands.stream().map(
                DocumentDedupRepository.Candidate::id).toList(),
                "only the qualifying rows may pass the filters, got " + cands);
    }

    /** Union of both channels: deduplicated by id and ordered created_at ASC, id ASC. */
    @Test
    void mergedCandidatesAreDeduplicatedAndOldestFirst() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-10T10:00:00Z");
        // Found by BOTH channels (same text and same embedding as the target).
        UUID oldest = seedCell(DOC_ORIGINAL, VEC_A, "consumption:a", "committed", t0);
        // Found by the lexical channel only (embedding on a different axis).
        UUID middle = seedCell(DOC_ORIGINAL, VEC_B, "consumption:b", "committed", t0.plusDays(1));
        // Found by the vector channel only (identical embedding, unrelated text).
        UUID newest = seedCell("Kontoauszug Nummer 17 Buchungen 2026", VEC_A, "consumption:c",
                "committed", t0.plusDays(2));
        UUID rescan = seedCell(DOC_RESCAN, VEC_A, "consumption:d", "committed", t0.plusDays(3));

        List<DocumentDedupRepository.Candidate> cands =
                repo.findSimilarOlderCandidates(rescan, 0.92, 10);

        assertEquals(List.of(oldest, middle, newest), cands.stream().map(
                DocumentDedupRepository.Candidate::id).toList(), "merged order, got " + cands);
    }

    /** Same created_at on both channels' rows: the id tie-break keeps the order total. */
    @Test
    void mergedCandidatesUseIdAsTieBreakOnEqualCreatedAt() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-11T10:00:00Z");
        UUID a = seedCell(DOC_ORIGINAL, VEC_A, "consumption:a", "committed", t0);
        UUID b = seedCell(DOC_ORIGINAL, VEC_B, "consumption:b", "committed", t0);
        UUID rescan = seedCell(DOC_RESCAN, VEC_A, "consumption:d", "committed", t0.plusDays(1));

        List<UUID> ids = repo.findSimilarOlderCandidates(rescan, 0.92, 10).stream()
                .map(DocumentDedupRepository.Candidate::id).toList();

        List<UUID> expected = a.compareTo(b) < 0 ? List.of(a, b) : List.of(b, a);
        assertEquals(expected, ids);
    }

    /**
     * A target with no qualifying lexemes must skip the channel instead of issuing
     * {@code to_tsquery('simple', '')}.
     *
     * <p>SMOKE TEST, and deliberately labelled as one: the result cannot distinguish the two
     * implementations. An empty tsquery matches nothing, so a repository that skipped the guard and
     * ran the query anyway would return the same empty list — it would only differ in emitting a
     * server NOTICE, which is not observable from here. What this test does prove is the
     * PRECONDITION (the seeded content really yields zero lexemes passing the form filter) and that
     * the path is exercised without error. The guard itself is enforced by review, not by SQL.
     */
    @Test
    void emptyLexemeSetSkipsTheLexicalChannel() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-12T10:00:00Z");
        String numbersOnly = "4711 88123 2026 EUR";
        seedCell(numbersOnly, VEC_B, "consumption:a", "committed", t0);
        UUID rescan = seedCell(numbersOnly, VEC_A, "consumption:b", "committed", t0.plusDays(1));

        // Precondition, asserted rather than assumed: the same form filter the channel applies
        // ('^[a-zäöüß]{6,}$') selects nothing from this target's tsv.
        long lexemes = dsl.fetchOne(
                "SELECT count(*) AS n FROM cells c, unnest(c.tsv) AS l "
                + "WHERE c.id = ? AND l.lexeme ~ '^[a-zäöüß]{6,}$'", rescan).get("n", Long.class);
        assertEquals(0L, lexemes, "test setup must produce an empty lexeme set");

        List<DocumentDedupRepository.Candidate> cands =
                repo.findSimilarOlderCandidates(rescan, 0.92, 10);

        assertTrue(cands.isEmpty(), "no qualifying lexemes -> no lexical channel, got " + cands);
    }

    /** candidateK is a per-channel LIMIT, so the union may hold up to 2k rows. */
    @Test
    void candidateKAppliesPerChannel() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-13T10:00:00Z");
        for (int i = 0; i < 3; i++) {
            // Lexical-only hits (VEC_B is orthogonal to the target's VEC_A).
            seedCell(DOC_ORIGINAL + " Blatt " + i, VEC_B, "consumption:l" + i, "committed",
                    t0.plusMinutes(i));
        }
        for (int i = 0; i < 3; i++) {
            // Vector-only hits.
            seedCell("Kontoauszug Nummer " + i, VEC_A, "consumption:v" + i, "committed",
                    t0.plusMinutes(10 + i));
        }
        UUID rescan = seedCell(DOC_RESCAN, VEC_A, "consumption:t", "committed", t0.plusDays(1));

        assertEquals(4, repo.findSimilarOlderCandidates(rescan, 0.92, 2).size(),
                "k=2 per channel over two disjoint channels must yield 4 rows");
    }

    /** A lexically overlapping but genuinely different document is recalled — the caller's text
     *  gate, not the channel, is what rejects it. */
    @Test
    void lexicalChannelRecallsOverlappingButDifferentDocument() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-14T10:00:00Z");
        UUID other = seedCell(DOC_DIFFERENT, VEC_B, "consumption:a", "committed", t0);
        UUID rescan = seedCell(DOC_RESCAN, VEC_A, "consumption:b", "committed", t0.plusDays(1));

        List<DocumentDedupRepository.Candidate> cands =
                repo.findSimilarOlderCandidates(rescan, 0.92, 10);

        assertEquals(1, cands.size());
        assertEquals(other, cands.get(0).id());
        assertTrue(TextSimilarity.similarity(DOC_RESCAN, cands.get(0).content()) < 0.85,
                "the text gate must reject it");
    }

    // ---------------------------------------------------------------------------------------
    // Status scope: dedup targets AND candidates are 'committed' or 'pending'. 'rejected' stays
    // out everywhere — those cells are not archive content.
    // ---------------------------------------------------------------------------------------

    @Test
    void findTargetAcceptsPendingAndRejectsRejected() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-05T10:00:00Z");
        UUID pending = seedCell("Rechnung 4711", VEC_A, "consumption:p", "pending", t0);
        UUID rejected = seedCell("Rechnung 4711", VEC_A, "consumption:r", "rejected", t0);

        assertTrue(repo.findTarget(pending).isPresent(), "pending cell is a valid dedup target");
        assertFalse(repo.findTarget(rejected).isPresent(), "rejected cell is never a dedup target");
    }

    /** Both channels see 'pending' candidates and neither sees 'rejected' ones. */
    @Test
    void candidateChannelsIncludePendingAndExcludeRejected() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-15T10:00:00Z");
        UUID pending = seedCell(DOC_ORIGINAL, VEC_A, "consumption:p", "pending", t0.minusDays(2));
        seedCell(DOC_ORIGINAL, VEC_A, "consumption:r", "rejected", t0.minusDays(1));
        UUID rescan = seedCell(DOC_RESCAN, VEC_A, "consumption:t", "committed", t0);

        List<DocumentDedupRepository.Candidate> cands =
                repo.findSimilarOlderCandidates(rescan, 0.92, 10);

        assertEquals(List.of(pending), cands.stream().map(
                DocumentDedupRepository.Candidate::id).toList(),
                "pending must be recalled, rejected must not, got " + cands);
    }

    /**
     * A discarded 'pending' cell must also become 'rejected'. The pending_approvals view filters on
     * status alone, with no liveness check (and so does WriteToolRepository.approvePending), so a
     * soft-deleted pending cell would otherwise sit in the approval queue forever and could be
     * approved into a committed, soft-deleted, duplicate-linked ghost.
     */
    @Test
    void linkAndSoftDeleteRejectsADiscardedPendingCell() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-16T10:00:00Z");
        UUID original = seedCell(DOC_ORIGINAL, VEC_A, "consumption:a", "committed", t0);
        UUID dup = seedCell(DOC_RESCAN, VEC_A, "consumption:b", "pending", t0.plusDays(1));
        assertEquals(1L, pendingApprovalRows(dup), "precondition: the pending cell is queued");

        repo.linkAndSoftDelete(dup, original, "auto-dedup note", "system-dedup");

        assertEquals("rejected", statusOf(dup), "a discarded pending cell must end up rejected");
        assertEquals(0L, pendingApprovalRows(dup), "it must leave the approval queue");
        assertEquals(0, new WriteToolRepository(dsl).approvePending(List.of(dup), "committed"),
                "approving it must be a no-op");
        assertEquals("rejected", statusOf(dup), "and must not resurrect it as committed");
    }

    /** A discarded 'committed' cell keeps its status — only pending ones are re-labelled. */
    @Test
    void linkAndSoftDeleteKeepsTheStatusOfADiscardedCommittedCell() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-17T10:00:00Z");
        UUID original = seedCell(DOC_ORIGINAL, VEC_A, "consumption:a", "committed", t0);
        UUID dup = seedCell(DOC_RESCAN, VEC_A, "consumption:b", "committed", t0.plusDays(1));

        repo.linkAndSoftDelete(dup, original, "auto-dedup note", "system-dedup");

        assertEquals("committed", statusOf(dup));
        assertFalse(repo.findTarget(dup).isPresent(), "still soft-deleted");
    }

    // ---------------------------------------------------------------------------------------
    // Facts of a discarded duplicate must not stay live pointing at a dead source.
    // ---------------------------------------------------------------------------------------

    @Test
    void invalidatesTheDuplicatesFactsWhenTheOriginalAlreadyHasSome() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        UUID original = seedCell("policy", VEC_A, "consumption:a", "committed", OffsetDateTime.now().minusDays(2));
        UUID duplicate = seedCell("policy", VEC_A, "consumption:b", "committed", OffsetDateTime.now());
        UUID keptFact = seedFact(original, "SYNTHETIC INSURER", "policy_number", "1000000001");
        UUID dupFact = seedFact(duplicate, "SYNTHETIC INSURER", "policy_number", "1000000001");

        repo.linkAndSoftDelete(duplicate, original, "note", "test");

        assertFalse(factIsLive(dupFact), "the duplicate's fact must not stay live");
        assertTrue(factIsLive(keptFact), "the original's fact must be untouched");
        assertEquals(duplicate, factSource(dupFact), "invalidation must not move the fact");
    }

    @Test
    void repointsTheDuplicatesFactsWhenTheOriginalHasNone() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        UUID original = seedCell("policy", VEC_A, "consumption:a", "committed", OffsetDateTime.now().minusDays(2));
        UUID duplicate = seedCell("policy", VEC_A, "consumption:b", "committed", OffsetDateTime.now());
        UUID dupFact = seedFact(duplicate, "SYNTHETIC INSURER", "policy_number", "1000000001");

        repo.linkAndSoftDelete(duplicate, original, "note", "test");

        assertTrue(factIsLive(dupFact), "the only copy of the fact must survive");
        assertEquals(original, factSource(dupFact), "it must now belong to the surviving cell");
    }

    @Test
    void leavesAlreadyInvalidatedFactsOfTheDuplicateAlone() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        UUID original = seedCell("policy", VEC_A, "consumption:a", "committed", OffsetDateTime.now().minusDays(2));
        UUID duplicate = seedCell("policy", VEC_A, "consumption:b", "committed", OffsetDateTime.now());
        seedFact(original, "SYNTHETIC INSURER", "policy_number", "1000000001");
        UUID dead = seedFact(duplicate, "SYNTHETIC INSURER", "premium", "12,34 EUR");
        dsl.execute("UPDATE facts SET valid_until = now() - interval '1 day' WHERE id = ?", dead);
        OffsetDateTime before = dsl.fetchOne("SELECT valid_until FROM facts WHERE id = ?", dead)
                .get("valid_until", OffsetDateTime.class);

        repo.linkAndSoftDelete(duplicate, original, "note", "test");

        OffsetDateTime after = dsl.fetchOne("SELECT valid_until FROM facts WHERE id = ?", dead)
                .get("valid_until", OffsetDateTime.class);
        assertEquals(before, after, "an already-dead fact must not be re-stamped");
    }

    @Test
    void stillRejectsAPendingDuplicateAndWritesTheTunnel() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        UUID original = seedCell("policy", VEC_A, "consumption:a", "committed", OffsetDateTime.now().minusDays(2));
        UUID duplicate = seedCell("policy", VEC_A, "consumption:b", "pending", OffsetDateTime.now());
        seedFact(original, "SYNTHETIC INSURER", "policy_number", "1000000001");

        repo.linkAndSoftDelete(duplicate, original, "note", "test");

        assertEquals("rejected",
                dsl.fetchOne("SELECT status FROM cells WHERE id = ?", duplicate).get("status"));
        assertEquals(1, dsl.fetchOne(
                "SELECT count(*) AS n FROM tunnels WHERE from_cell = ? AND relation = 'duplicate_of'",
                duplicate).get("n", Long.class).intValue());
    }

    /**
     * The {@code duplicate_of} original is itself dead but a revision superseded it and is still
     * live: the successor walk over {@code parent_id} must find that successor and land the facts
     * there, not on the dead original.
     */
    @Test
    void repointsToTheLiveSuccessorWhenTheOriginalIsSoftDeleted() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        UUID deadOriginal = seedCell("policy", VEC_A, "consumption:a", "committed", OffsetDateTime.now().minusDays(3));
        softDelete(deadOriginal);
        UUID successor = seedCellWithParent(deadOriginal, "policy", VEC_A, "consumption:c", "committed",
                OffsetDateTime.now().minusDays(2));
        UUID duplicate = seedCell("policy", VEC_A, "consumption:b", "committed", OffsetDateTime.now());
        UUID dupFact = seedFact(duplicate, "SYNTHETIC INSURER", "policy_number", "1000000001");

        repo.linkAndSoftDelete(duplicate, deadOriginal, "note", "test");

        assertTrue(factIsLive(dupFact), "the fact must survive on the live successor");
        assertEquals(successor, factSource(dupFact), "must land on the successor, not the dead original");
    }

    /**
     * The {@code duplicate_of} original is dead and has no live successor to walk to: nothing may
     * be touched, and the caller must be told the fact was skipped rather than silently destroyed
     * or wrongly repointed.
     */
    @Test
    void skipsWhenTheOriginalIsSoftDeletedWithNoLiveSuccessor() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        UUID deadOriginal = seedCell("policy", VEC_A, "consumption:a", "committed", OffsetDateTime.now().minusDays(2));
        softDelete(deadOriginal);
        UUID duplicate = seedCell("policy", VEC_A, "consumption:b", "committed", OffsetDateTime.now());
        UUID dupFact = seedFact(duplicate, "SYNTHETIC INSURER", "policy_number", "1000000001");

        DocumentDedupRepository.FactSettlement result =
                repo.reassignOrInvalidateFacts(dsl, duplicate, deadOriginal);

        assertEquals(DocumentDedupRepository.FactSettlement.Branch.SKIPPED, result.branch());
        assertEquals(0, result.rowsAffected(), "a skip touches no rows");
        assertTrue(factIsLive(dupFact), "no live target -> the fact must not be touched at all");
        assertEquals(duplicate, factSource(dupFact), "must not be repointed onto a resolved-nothing target");
    }

    /**
     * A skip must not silently drop the OTHER two effects of {@code linkAndSoftDelete}: the audit
     * tunnel and the soft-delete of the duplicate cell still have to happen even when its facts
     * could not be settled onto any live target. This exercises the public path (not the
     * package-private helper with a non-transactional {@code dsl}), which is what
     * {@code DocumentDedupService.discard} actually calls in production.
     *
     * <p>Also asserts the WARN log line itself, not just its side effects: a silently reverted
     * skip-log is exactly the failure mode item 1 (of the round-2 review) exists to prevent, and
     * without this assertion nothing would catch that regression.
     */
    @Test
    void skipStillWritesTheTunnelAndSoftDeletesTheDuplicate() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        UUID deadOriginal = seedCell("policy", VEC_A, "consumption:a", "committed", OffsetDateTime.now().minusDays(2));
        softDelete(deadOriginal);
        UUID duplicate = seedCell("policy", VEC_A, "consumption:b", "committed", OffsetDateTime.now());
        seedFact(duplicate, "SYNTHETIC INSURER", "policy_number", "1000000001");

        ch.qos.logback.classic.Logger repoLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DocumentDedupRepository.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        repoLogger.addAppender(appender);
        try {
            repo.linkAndSoftDelete(duplicate, deadOriginal, "note", "test");
        } finally {
            repoLogger.detachAppender(appender);
        }

        assertEquals(1, appender.list.stream()
                        .filter(e -> e.getLevel() == Level.WARN)
                        .filter(e -> e.getFormattedMessage().contains(duplicate.toString()))
                        .count(),
                "a skip must log exactly one WARN naming the discarded cell, got: " + appender.list);

        assertFalse(repo.findTarget(duplicate).isPresent(), "duplicate must still be soft-deleted on a skip");
        assertEquals(1, dsl.fetchOne(
                "SELECT count(*) AS n FROM tunnels WHERE from_cell = ? AND relation = 'duplicate_of'",
                duplicate).get("n", Long.class).intValue(),
                "the audit tunnel must still be written on a skip");
    }

    /**
     * A live PENDING original must resolve as a fact target, not just a committed one — dedup
     * itself already treats pending as a valid original (DEDUP_STATUS_FILTER), and a live pending
     * cell is not the "no live target" case the SKIPPED branch exists for.
     */
    @Test
    void resolvesALivePendingOriginalAsAFactTarget() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        UUID pendingOriginal = seedCell("policy", VEC_A, "consumption:a", "pending", OffsetDateTime.now().minusDays(2));
        UUID duplicate = seedCell("policy", VEC_A, "consumption:b", "committed", OffsetDateTime.now());
        UUID dupFact = seedFact(duplicate, "SYNTHETIC INSURER", "policy_number", "1000000001");

        repo.linkAndSoftDelete(duplicate, pendingOriginal, "note", "test");

        assertTrue(factIsLive(dupFact), "a live pending original is a valid fact target");
        assertEquals(pendingOriginal, factSource(dupFact), "the fact must land on the pending original");
    }

    /**
     * The successor walk must follow the chain past a single hop: original -> dead revision ->
     * live revision. Measured maximum chain depth on production is 1, but the walk itself is not
     * hardcoded to a single step, so this pins that it actually iterates.
     */
    @Test
    void walksMultipleHopsToReachTheLiveSuccessor() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        UUID deadOriginal = seedCell("policy", VEC_A, "consumption:a", "committed", OffsetDateTime.now().minusDays(4));
        softDelete(deadOriginal);
        UUID deadRevision = seedCellWithParent(deadOriginal, "policy", VEC_A, "consumption:c", "committed",
                OffsetDateTime.now().minusDays(3));
        softDelete(deadRevision);
        UUID liveSuccessor = seedCellWithParent(deadRevision, "policy", VEC_A, "consumption:d", "committed",
                OffsetDateTime.now().minusDays(2));
        UUID duplicate = seedCell("policy", VEC_A, "consumption:b", "committed", OffsetDateTime.now());
        UUID dupFact = seedFact(duplicate, "SYNTHETIC INSURER", "policy_number", "1000000001");

        repo.linkAndSoftDelete(duplicate, deadOriginal, "note", "test");

        assertTrue(factIsLive(dupFact), "the fact must survive on the two-hops-away live successor");
        assertEquals(liveSuccessor, factSource(dupFact));
    }

    /**
     * The repoint branch must also rewrite {@code subject} when it equals the discarded cell's own
     * id (SummarizerService.persistFacts writes {@code subject = cellId.toString()} for most facts),
     * and must leave a real-entity subject untouched.
     */
    @Test
    void repointRewritesSubjectOnlyWhenItNamesTheDiscardedCell() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        UUID original = seedCell("policy", VEC_A, "consumption:a", "committed", OffsetDateTime.now().minusDays(2));
        UUID duplicate = seedCell("policy", VEC_A, "consumption:b", "committed", OffsetDateTime.now());
        UUID selfNamedFact = seedFact(duplicate, duplicate.toString(), "document_type", "policy");
        UUID entityFact = seedFact(duplicate, "SYNTHETIC INSURER", "policy_number", "1000000001");

        repo.linkAndSoftDelete(duplicate, original, "note", "test");

        assertEquals(original.toString(), factSubject(selfNamedFact),
                "a subject naming the discarded cell must be rewritten to the surviving cell");
        assertEquals("SYNTHETIC INSURER", factSubject(entityFact),
                "a real-entity subject must be left untouched");
        assertEquals(original, factSource(selfNamedFact));
        assertEquals(original, factSource(entityFact));
    }

    /** An already-dead fact of the duplicate must be left alone under the repoint branch too, not
     *  only under the invalidate branch. */
    @Test
    void leavesAlreadyInvalidatedFactsOfTheDuplicateAloneUnderTheRepointBranch() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        UUID original = seedCell("policy", VEC_A, "consumption:a", "committed", OffsetDateTime.now().minusDays(2));
        UUID duplicate = seedCell("policy", VEC_A, "consumption:b", "committed", OffsetDateTime.now());
        UUID dead = seedFact(duplicate, "SYNTHETIC INSURER", "premium", "12,34 EUR");
        dsl.execute("UPDATE facts SET valid_until = now() - interval '1 day' WHERE id = ?", dead);
        OffsetDateTime before = dsl.fetchOne("SELECT valid_until FROM facts WHERE id = ?", dead)
                .get("valid_until", OffsetDateTime.class);
        UUID beforeSource = factSource(dead);

        repo.linkAndSoftDelete(duplicate, original, "note", "test");

        OffsetDateTime after = dsl.fetchOne("SELECT valid_until FROM facts WHERE id = ?", dead)
                .get("valid_until", OffsetDateTime.class);
        assertEquals(before, after, "an already-dead fact must not be re-stamped");
        assertEquals(beforeSource, factSource(dead), "an already-dead fact must not be repointed either");
    }

    private String statusOf(UUID id) {
        return dsl.fetchOne("SELECT status FROM cells WHERE id = ?", id).get("status", String.class);
    }

    private long pendingApprovalRows(UUID id) {
        return dsl.fetchOne("SELECT count(*) AS n FROM pending_approvals WHERE id = ?", id)
                .get("n", Long.class);
    }

    // ---------------------------------------------------------------------------------------
    // Keyset cursor for the backfill walk
    // ---------------------------------------------------------------------------------------

    /**
     * The page is bounded by (created_at, id) > cursor, never by OFFSET: soft-deletes shift the
     * window, and a plain LIMIT never advances at all because a non-duplicate cell is not deleted
     * and therefore reappears in the very same first page.
     */
    @Test
    void liveConsumptionCellsArePagedByKeysetCursor() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-18T10:00:00Z");
        UUID a = seedCell("Rechnung 1001", VEC_A, "consumption:a", "committed", t0);
        UUID b = seedCell("Rechnung 1002", VEC_A, "consumption:b", "pending", t0.plusMinutes(1));
        UUID c = seedCell("Rechnung 1003", VEC_A, "consumption:c", "committed", t0.plusMinutes(2));
        seedCell("Rechnung 1004", VEC_A, "consumption:d", "rejected", t0.plusMinutes(3));
        seedCell("Rechnung 1005", VEC_A, "manual:x", "committed", t0.plusMinutes(4));
        UUID deleted = seedCell("Rechnung 1006", VEC_A, "consumption:e", "committed", t0.plusMinutes(5));
        softDelete(deleted);

        assertEquals(3, repo.countLiveConsumptionCellsAfter(null, null));

        List<DocumentDedupRepository.LiveCell> page1 =
                repo.findLiveConsumptionCellIdsOldestFirst(null, null, 2);
        assertEquals(List.of(a, b), page1.stream()
                .map(DocumentDedupRepository.LiveCell::id).toList());
        assertEquals(t0, page1.get(0).createdAt());

        DocumentDedupRepository.LiveCell last = page1.get(1);
        assertEquals(1, repo.countLiveConsumptionCellsAfter(last.createdAt(), last.id()));

        List<DocumentDedupRepository.LiveCell> page2 =
                repo.findLiveConsumptionCellIdsOldestFirst(last.createdAt(), last.id(), 2);
        assertEquals(List.of(c), page2.stream()
                .map(DocumentDedupRepository.LiveCell::id).toList());

        DocumentDedupRepository.LiveCell end = page2.get(0);
        assertEquals(0, repo.countLiveConsumptionCellsAfter(end.createdAt(), end.id()));
        assertTrue(repo.findLiveConsumptionCellIdsOldestFirst(end.createdAt(), end.id(), 2).isEmpty());
    }

    /**
     * Equal created_at is explicitly allowed, so the cursor must compare the id as well — otherwise
     * a page boundary that lands inside a same-timestamp run either loops on it or skips it.
     *
     * <p>The two ids are fixed rather than random, and chosen so that Java and Postgres agree on
     * their order: Java's {@code UUID.compareTo} compares the two halves as SIGNED longs while
     * Postgres compares the 16 bytes unsigned, so for a random pair the two disagree about half the
     * time. With both high halves zero (hence positive) the orderings coincide, and the expectation
     * below can be stated outright instead of being read back from the implementation.
     */
    @Test
    void keysetCursorUsesIdAsTieBreakOnEqualCreatedAt() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-19T10:00:00Z");
        UUID lower = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID higher = UUID.fromString("00000000-0000-0000-0000-000000000002");
        seedCellWithId(higher, "Rechnung 2002", VEC_A, "consumption:b", "committed", t0);
        seedCellWithId(lower, "Rechnung 2001", VEC_A, "consumption:a", "committed", t0);

        assertEquals(List.of(lower, higher),
                repo.findLiveConsumptionCellIdsOldestFirst(null, null, 10)
                        .stream().map(DocumentDedupRepository.LiveCell::id).toList(),
                "id breaks the tie inside one created_at");
        assertEquals(List.of(higher),
                repo.findLiveConsumptionCellIdsOldestFirst(t0, lower, 10)
                        .stream().map(DocumentDedupRepository.LiveCell::id).toList(),
                "a cursor inside the same-timestamp run must neither repeat nor skip");
    }
}
