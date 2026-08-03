package com.hivemem.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

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
        UUID id = UUID.randomUUID();
        dsl.execute(
                "INSERT INTO cells (id, content, embedding, source, status, created_at, valid_from) "
                + "VALUES (?, ?, ?::vector, ?, ?, ?::timestamptz, now())",
                id, content, embedding, source, status, createdAt);
        return id;
    }

    private void linkAttachment(UUID cellId, UUID attachmentId) {
        dsl.execute(
                "INSERT INTO cell_attachments (cell_id, attachment_id, extraction_source) "
                + "VALUES (?, ?, true)", cellId, attachmentId);
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
        UUID dup = seedCell("Rechnung 4711", VEC_A, "consumption:b", "committed", t0);
        linkAttachment(dup, att);

        assertEquals(1, repo.countOtherLiveCellsForAttachment(att, UUID.randomUUID()));
        assertTrue(repo.softDeleteCell(dup) >= 1);
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

    private static final String DOC_ORIGINAL =
            "Zusatzvereinbarung zwischen der Beispielfirma Musterbau GmbH und dem Auftragnehmer "
            + "ueber die Erbringung von Beratungsleistungen im Geschaeftsjahr 2026. "
            + "Die Vertragsparteien vereinbaren eine monatliche Verguetung in Hoehe von "
            + "eintausendzweihundert Euro zuzueglich der gesetzlichen Umsatzsteuer. "
            + "Kuendigungsfrist drei Monate zum Quartalsende, Gerichtsstand ist Musterstadt. "
            + "Nebenabreden beduerfen der Schriftform, muendliche Zusagen sind unwirksam.";

    /**
     * The re-scan: identical except that the ORIGINAL is one token SHORTER (it lacks "unwirksam").
     * This is the direction that a conjunctive plainto_tsquery would miss — the older cell is not a
     * lexical superset of the newer one.
     */
    private static final String DOC_RESCAN = DOC_ORIGINAL.replace(" sind unwirksam", " sind");

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

    /** Same guards as the vector channel: source, liveness, status, strictly older. */
    @Test
    void lexicalChannelAppliesTheSameFiltersAsTheVectorChannel() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-09T10:00:00Z");
        UUID rescan = seedCell(DOC_RESCAN, VEC_A, "consumption:b", "committed", t0);
        seedCell(DOC_ORIGINAL, VEC_B, "manual:x", "committed", t0.minusDays(1));      // not a scan
        seedCell(DOC_ORIGINAL, VEC_B, "consumption:c", "committed", t0.plusDays(1));  // newer
        seedCell(DOC_ORIGINAL, VEC_B, "consumption:d", "pending", t0.minusDays(1));   // not committed
        seedCell(DOC_ORIGINAL, VEC_B, "consumption:e", "rejected", t0.minusDays(1));  // rejected
        UUID deleted = seedCell(DOC_ORIGINAL, VEC_B, "consumption:f", "committed", t0.minusDays(1));
        softDelete(deleted);                                                          // not live

        List<DocumentDedupRepository.Candidate> cands =
                repo.findSimilarOlderCandidates(rescan, 0.92, 10);

        assertTrue(cands.isEmpty(), "expected no candidates, got " + cands);
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

    /** A target with no qualifying lexemes must simply skip the channel (no to_tsquery('') error). */
    @Test
    void emptyLexemeSetSkipsTheLexicalChannel() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-12T10:00:00Z");
        seedCell("4711 88123 2026 EUR", VEC_B, "consumption:a", "committed", t0);
        UUID rescan = seedCell("4711 88123 2026 EUR", VEC_A, "consumption:b", "committed", t0.plusDays(1));

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

    @Test
    void findTargetIgnoresNonCommitted() {
        DocumentDedupRepository repo = new DocumentDedupRepository(dsl);
        OffsetDateTime t0 = OffsetDateTime.parse("2026-06-05T10:00:00Z");
        UUID pending = seedCell("Rechnung 4711", VEC_A, "consumption:p", "pending", t0);
        assertFalse(repo.findTarget(pending).isPresent(), "pending cell is not a valid dedup target");
    }
}
