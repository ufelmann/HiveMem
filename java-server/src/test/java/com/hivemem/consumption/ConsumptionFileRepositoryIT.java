package com.hivemem.consumption;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConsumptionFileRepositoryIT extends ConsumptionITSupport {

    private ConsumptionFileRepository repo;

    @BeforeEach
    void setUp() {
        dsl.execute("DELETE FROM consumption_file");
        repo = new ConsumptionFileRepository(dsl);
    }

    @Test
    void upsertIncrementsAttemptsOnSameHash() {
        repo.startProcessing("abc123", "scan.pdf");
        repo.startProcessing("abc123", "scan.pdf");

        var row = repo.findByHash("abc123");
        assertTrue(row.isPresent());
        assertEquals(2, row.get().attempts());
        assertEquals("processing", row.get().state());
    }

    @Test
    void markDoneAndFailedSetState() {
        repo.startProcessing("h1", "done.pdf");
        repo.markDone("h1");
        var done = repo.findByHash("h1");
        assertTrue(done.isPresent());
        assertEquals("done", done.get().state());

        repo.startProcessing("h2", "fail.pdf");
        repo.markFailed("h2", "boom");
        var failed = repo.findByHash("h2");
        assertTrue(failed.isPresent());
        assertEquals("failed", failed.get().state());
        assertEquals("boom", failed.get().lastError());
    }

    @Test
    void findStaleProcessingReturnsOldRows() {
        repo.startProcessing("h3", "stale.pdf");
        // Backdate the row so it appears stale (older than 60 seconds)
        dsl.execute("UPDATE consumption_file SET updated_at = now() - interval '120 seconds' WHERE sha256=?", "h3");

        List<ConsumptionFileRepository.Row> stale = repo.findStaleProcessing(60, 100);
        assertTrue(stale.stream().anyMatch(r -> r.sha256().equals("h3")),
                "expected h3 in stale results but got: " + stale);
    }

    /** findStaleProcessing must cover 'staged' as well as 'processing'. Without this case the query
     *  could be narrowed back to `WHERE state = 'processing'` and the whole suite would stay green,
     *  while every file stranded between stage() and dispatch became unrecoverable. */
    @Test
    void findStaleProcessingAlsoReturnsStagedRows() {
        repo.stage("h3s", "stranded.pdf");
        dsl.execute("UPDATE consumption_file SET updated_at = now() - interval '120 seconds' WHERE sha256=?", "h3s");

        List<ConsumptionFileRepository.Row> stale = repo.findStaleProcessing(60, 100);
        assertTrue(stale.stream().anyMatch(
                        r -> r.sha256().equals("h3s") && r.state().equals("staged")),
                "expected the 'staged' row h3s in stale results but got: " + stale);
    }

    /** A fresh 'staged' row must NOT be selected — only staleness makes it interesting. */
    @Test
    void findStaleProcessingIgnoresFreshStagedRows() {
        repo.stage("h3f", "fresh.pdf");

        List<ConsumptionFileRepository.Row> stale = repo.findStaleProcessing(60, 100);
        assertTrue(stale.stream().noneMatch(r -> r.sha256().equals("h3f")));
    }

    @Test
    void recordPageStatsPersistsTotalDegradedAndBlankCounts() {
        repo.startProcessing("h6", "stats.pdf");
        repo.recordPageStats("h6", 12, 3, 2);

        var row = dsl.fetchOne(
                "SELECT total_pages, degraded_pages, blank_pages FROM consumption_file WHERE sha256 = ?", "h6");
        assertNotNull(row, "expected a row for h6");
        assertEquals(12, row.get("total_pages", Integer.class));
        assertEquals(3, row.get("degraded_pages", Integer.class));
        assertEquals(2, row.get("blank_pages", Integer.class));
    }

    /** blank_pages is nullable: a row that predates V0055 (or was never given page stats at all)
     *  must not have an invented value — rows recorded before V0055 must stay NULL, not 0. */
    @Test
    void blankPagesIsNullUntilRecorded() {
        repo.startProcessing("h6n", "no-stats-yet.pdf");

        var row = dsl.fetchOne(
                "SELECT blank_pages FROM consumption_file WHERE sha256 = ?", "h6n");
        assertNotNull(row, "expected a row for h6n");
        assertNull(row.get("blank_pages", Integer.class));
    }

    /** findDegradedBatches must surface blank_pages alongside total/degraded so the review queue
     *  can show a batch that lost pages to the blank filter, not just to failed extraction. */
    @Test
    void findDegradedBatchesIncludesBlankPageCount() {
        repo.startProcessing("h7", "degraded-with-blanks.pdf");
        repo.recordPageStats("h7", 20, 2, 5);

        List<ConsumptionFileRepository.DegradedBatch> batches = repo.findDegradedBatches(2, 0.60, 50);

        assertEquals(1, batches.size());
        assertEquals(5, batches.get(0).blankPages());
    }

    /** The real batches that motivated lowering the floor to 1: a single degraded page out of 15
     *  or 26 total pages, both well above the 2 % ratio branch, must reach the queue. */
    @Test
    void findDegradedBatchesSurfacesASingleDegradedPageInA15PageBatch() {
        repo.startProcessing("prod-15", "batch-15.pdf");
        repo.recordPageStats("prod-15", 15, 1, 0);

        List<ConsumptionFileRepository.DegradedBatch> batches = repo.findDegradedBatches(1, 0.60, 50);

        assertTrue(batches.stream().anyMatch(b -> b.sha256().equals("prod-15")),
                "1 degraded page out of 15 must be visible with minDegraded=1");
    }

    @Test
    void findDegradedBatchesSurfacesASingleDegradedPageInA26PageBatch() {
        repo.startProcessing("prod-26", "batch-26.pdf");
        repo.recordPageStats("prod-26", 26, 1, 0);

        List<ConsumptionFileRepository.DegradedBatch> batches = repo.findDegradedBatches(1, 0.60, 50);

        assertTrue(batches.stream().anyMatch(b -> b.sha256().equals("prod-26")),
                "1 degraded page out of 26 must be visible with minDegraded=1");
    }

    /** total_pages = 0 must never divide; a batch that died before analysis stays invisible
     *  regardless of the degraded floor. */
    @Test
    void findDegradedBatchesHidesAZeroTotalPagesRowEvenWithOneDegradedPage() {
        repo.startProcessing("zero-pages", "zero.pdf");
        repo.recordPageStats("zero-pages", 0, 1, 0);

        List<ConsumptionFileRepository.DegradedBatch> batches = repo.findDegradedBatches(1, 0.60, 50);

        assertTrue(batches.stream().noneMatch(b -> b.sha256().equals("zero-pages")),
                "total_pages = 0 must never surface, even with a degraded page recorded");
    }

    /** A single degraded page out of 100 is 1 % — below the 2 % ratio branch — and must stay
     *  invisible even though the floor of 1 is satisfied. */
    @Test
    void findDegradedBatchesHidesOneDegradedPageOutOf100() {
        repo.startProcessing("large-batch", "large.pdf");
        repo.recordPageStats("large-batch", 100, 1, 0);

        List<ConsumptionFileRepository.DegradedBatch> batches = repo.findDegradedBatches(1, 0.60, 50);

        assertTrue(batches.stream().noneMatch(b -> b.sha256().equals("large-batch")),
                "1 % degraded is below the 2 % ratio floor and must stay invisible");
    }

    /** Second OR-branch: a batch with zero degraded pages but a blank-page ratio well above ordinary
     *  duplex (13 of 20 pages, 65 %) must reach the queue — this is the case the blank_pages column
     *  exists for. */
    @Test
    void findDegradedBatchesSurfacesABlankRatioOutlierWithZeroDegradedPages() {
        repo.startProcessing("blank-outlier", "blank-outlier.pdf");
        repo.recordPageStats("blank-outlier", 20, 0, 13);

        List<ConsumptionFileRepository.DegradedBatch> batches = repo.findDegradedBatches(1, 0.60, 50);

        assertTrue(batches.stream().anyMatch(b -> b.sha256().equals("blank-outlier")),
                "13/20 = 0.65 blank ratio must exceed the 0.60 alert threshold");
    }

    /** Pins the blankRatioAlert bind parameter: a ratio that clears 0.60 (the value every other IT
     *  in this file passes) must NOT clear a stricter 0.90 — without this, a SQL change that hardcoded
     *  the literal 0.60 instead of binding the parameter would leave the whole suite green. */
    @Test
    void findDegradedBatchesRespectsAStricterBlankRatioAlertBind() {
        repo.startProcessing("blank-outlier-strict", "blank-outlier-strict.pdf");
        repo.recordPageStats("blank-outlier-strict", 20, 0, 13);

        List<ConsumptionFileRepository.DegradedBatch> batches = repo.findDegradedBatches(1, 0.90, 50);

        assertTrue(batches.stream().noneMatch(b -> b.sha256().equals("blank-outlier-strict")),
                "13/20 = 0.65 clears 0.60 but not a 0.90 alert threshold");
    }

    /** Ordinary duplex scanning: half the pages are blank backsides. This must NOT alert — 0.50 is
     *  below the measured 0.60 threshold, which sits above every observed duplex ratio. */
    @Test
    void findDegradedBatchesHidesOrdinaryDuplexBlankRatio() {
        repo.startProcessing("duplex-normal", "duplex-normal.pdf");
        repo.recordPageStats("duplex-normal", 20, 0, 10);

        List<ConsumptionFileRepository.DegradedBatch> batches = repo.findDegradedBatches(1, 0.60, 50);

        assertTrue(batches.stream().noneMatch(b -> b.sha256().equals("duplex-normal")),
                "0.50 is ordinary duplex, at or below the 0.60 alert threshold");
    }

    /** blank_pages is nullable for pre-V0055 rows. NULL must never divide and must never surface via
     *  the blank-ratio branch. */
    @Test
    void findDegradedBatchesHidesNullBlankPages() {
        repo.startProcessing("null-blank", "null-blank.pdf");
        // recordPageStats always sets blank_pages; simulate a pre-V0055 row by updating total_pages
        // and degraded_pages directly, leaving blank_pages NULL.
        dsl.execute("UPDATE consumption_file SET total_pages = 20, degraded_pages = 0 WHERE sha256 = ?",
                "null-blank");

        List<ConsumptionFileRepository.DegradedBatch> batches = repo.findDegradedBatches(1, 0.60, 50);

        assertTrue(batches.stream().noneMatch(b -> b.sha256().equals("null-blank")),
                "NULL blank_pages must not divide and must not surface");
    }

    /** Stronger companion to {@link #findDegradedBatchesHidesNullBlankPages}: that test's NULL
     *  blank_pages alone passes equally under a NULL-unsafe {@code COALESCE(blank_pages, 0)}
     *  implementation, because 0/20 is also below threshold — it doesn't prove NULL-safety on its
     *  own. This row instead has a real blank-page ratio (15/20 = 0.75, well above 0.60) with
     *  {@code degraded_pages} left NULL: it must still stay invisible, and it also exercises the
     *  {@code degraded_pages IS NOT NULL} guard added to the blank branch — without that guard this
     *  row would reach the {@code DegradedBatch} mapper's primitive {@code int} fields and NPE on
     *  unboxing a NULL degraded_pages. */
    @Test
    void findDegradedBatchesHidesAHighBlankRatioRowWithNullDegradedPages() {
        repo.startProcessing("null-degraded-high-blank", "null-degraded-high-blank.pdf");
        dsl.execute("UPDATE consumption_file SET total_pages = 20, blank_pages = 15, "
                        + "degraded_pages = NULL WHERE sha256 = ?",
                "null-degraded-high-blank");

        List<ConsumptionFileRepository.DegradedBatch> batches = repo.findDegradedBatches(1, 0.60, 50);

        assertTrue(batches.stream().noneMatch(b -> b.sha256().equals("null-degraded-high-blank")),
                "a NULL degraded_pages must exclude the row even with a high blank ratio, "
                        + "and must not NPE the mapper");
    }

    @Test
    void updateFilenamePersistsMovedName() {
        repo.startProcessing("h4", "orig.pdf");
        repo.updateFilename("h4", "orig-1.pdf");

        var row = repo.findByHash("h4");
        assertTrue(row.isPresent());
        assertEquals("orig-1.pdf", row.get().filename(),
                "ledger must reflect the collision-suffixed name the mover actually used");
    }

    @Test
    void startProcessingRefreshesFilenameOnConflict() {
        repo.startProcessing("h5", "scan.pdf");
        // Re-staged under a collision-suffixed name (recovery sweep moveToRoot may rename)
        repo.startProcessing("h5", "scan-1.pdf");

        var row = repo.findByHash("h5");
        assertTrue(row.isPresent());
        assertEquals("scan-1.pdf", row.get().filename(),
                "conflict upsert must refresh the filename, not keep the stale original");
        assertEquals(2, row.get().attempts());
    }

    @Test
    void stagingTwiceThenProcessingOnceLeavesAttemptsAtOne() {
        // Regression for the halved-retry-budget bug: stage() must NOT count as an attempt.
        // Staging the same hash twice (e.g. re-poll before the move completes, or a re-fed
        // identical scan) followed by a single real processing pass must leave attempts at 1,
        // not 2 or 3 — otherwise findRetriableFailed's `attempts < maxAttempts` burns the retry
        // budget on registrations that never even reached processing.
        repo.stage("s1", "scan.pdf");
        repo.stage("s1", "scan.pdf");
        repo.startProcessing("s1", "scan.pdf");

        var row = repo.findByHash("s1");
        assertTrue(row.isPresent());
        assertEquals(1, row.get().attempts(),
                "stage() must not increment attempts; only startProcessing() may");
        assertEquals("processing", row.get().state());
    }

    @Test
    void stageDoesNotTouchAttemptsOnConflict() {
        // stage() alone (no processing pass) must leave attempts at 0 for a new row, and
        // untouched for a re-staged existing row.
        repo.stage("s2", "scan.pdf");
        var afterFirstStage = repo.findByHash("s2");
        assertTrue(afterFirstStage.isPresent());
        assertEquals(0, afterFirstStage.get().attempts());
        assertEquals("staged", afterFirstStage.get().state());

        repo.stage("s2", "scan-renamed.pdf");
        var afterSecondStage = repo.findByHash("s2");
        assertTrue(afterSecondStage.isPresent());
        assertEquals(0, afterSecondStage.get().attempts(), "re-staging must not increment attempts");
        assertEquals("scan-renamed.pdf", afterSecondStage.get().filename());
        assertEquals("staged", afterSecondStage.get().state());
    }

    @Test
    void findRetriableFailedRespectsAttemptsLimit() {
        // Row with attempts=1, maxAttempts=3 → should appear
        repo.startProcessing("hr1", "retry.pdf");
        repo.markFailed("hr1", "transient");

        // Row with attempts=3, maxAttempts=3 → should NOT appear
        repo.startProcessing("hr2", "exhausted.pdf");
        repo.startProcessing("hr2", "exhausted.pdf");
        repo.startProcessing("hr2", "exhausted.pdf");
        repo.markFailed("hr2", "exhausted");

        List<ConsumptionFileRepository.Row> retriable = repo.findRetriableFailed(3, 100);
        assertTrue(retriable.stream().anyMatch(r -> r.sha256().equals("hr1")),
                "hr1 (1 attempt < 3) should be retriable");
        assertTrue(retriable.stream().noneMatch(r -> r.sha256().equals("hr2")),
                "hr2 (3 attempts >= 3) should NOT be retriable");
    }

    /** M4: the review queue orders failures newest-first (the retry sweep's oldest-first ordering
     *  stays untouched), and does NOT hide rows that exhausted their retry budget — an exhausted row
     *  is precisely what a human should see. */
    @Test
    void findFailedNewestFirstOrdersByRecencyAndIncludesExhaustedRows() {
        repo.startProcessing("f-old", "old.pdf");
        repo.markFailed("f-old", "older failure");
        dsl.execute("UPDATE consumption_file SET updated_at = now() - interval '2 hours' WHERE sha256=?", "f-old");

        repo.startProcessing("f-exhausted", "exhausted.pdf");
        repo.startProcessing("f-exhausted", "exhausted.pdf");
        repo.startProcessing("f-exhausted", "exhausted.pdf");
        repo.markFailed("f-exhausted", "gave up");
        dsl.execute("UPDATE consumption_file SET updated_at = now() - interval '1 hour' WHERE sha256=?", "f-exhausted");

        repo.startProcessing("f-new", "new.pdf");
        repo.markFailed("f-new", "just broke");

        List<ConsumptionFileRepository.Row> failed = repo.findFailedNewestFirst(10);

        assertEquals(List.of("f-new", "f-exhausted", "f-old"),
                failed.stream().map(ConsumptionFileRepository.Row::sha256).toList(),
                "newest first, and the exhausted row must not be filtered out");
        // The sweep's own ordering must be the opposite and still budget-filtered.
        assertEquals("f-old", repo.findRetriableFailed(3, 10).get(0).sha256());
        assertTrue(repo.findRetriableFailed(3, 10).stream()
                        .noneMatch(r -> r.sha256().equals("f-exhausted")),
                "findRetriableFailed must keep excluding exhausted rows");
    }

    /** I4: rows that stalled rather than failed must be listable with enough identity to act on. */
    @Test
    void findStalledRowsReturnsAgedStagedAndProcessingRowsWithTheirAge() {
        repo.stage("st-staged", "queued.pdf");
        dsl.execute("UPDATE consumption_file SET updated_at = now() - interval '90 minutes' WHERE sha256=?", "st-staged");
        repo.startProcessing("st-processing", "working.pdf");
        dsl.execute("UPDATE consumption_file SET updated_at = now() - interval '45 minutes' WHERE sha256=?", "st-processing");
        repo.startProcessing("st-fresh", "fresh.pdf");           // too young
        repo.startProcessing("st-done", "finished.pdf");
        repo.markDone("st-done");
        dsl.execute("UPDATE consumption_file SET updated_at = now() - interval '3 hours' WHERE sha256=?", "st-done");

        List<ConsumptionFileRepository.StalledRow> stalled = repo.findStalledRows(1800, 100);

        assertEquals(List.of("st-staged", "st-processing"),
                stalled.stream().map(ConsumptionFileRepository.StalledRow::sha256).toList(),
                "only aged staged/processing rows, oldest first; done and fresh rows excluded");
        assertEquals("staged", stalled.get(0).state());
        assertEquals("queued.pdf", stalled.get(0).filename());
        assertTrue(stalled.get(0).ageSeconds() >= 5400, "age must be reported in seconds");
        assertNotNull(stalled.get(0).updatedAt());
    }

    @Test
    void knownFilenamesReturnsFilenamesWithAtLeastOneRow() {
        repo.startProcessing("k1", "known1.pdf");
        repo.startProcessing("k2", "known2.pdf");

        Set<String> known = repo.knownFilenames(List.of("known1.pdf", "known2.pdf", "unknown.pdf"));

        assertEquals(Set.of("known1.pdf", "known2.pdf"), known);
    }

    @Test
    void markMissingExhaustsRetryBudgetSoRowStopsBeingRetriable() {
        // Row starts under the retry limit, so it would normally be retriable.
        repo.startProcessing("m1", "missing.pdf");

        repo.markMissing("m1", 3);

        var row = repo.findByHash("m1");
        assertTrue(row.isPresent());
        assertEquals("failed", row.get().state());
        assertEquals("no physical file in processing/", row.get().lastError());
        assertTrue(row.get().attempts() >= 3, "attempts must be raised to at least the retry limit");

        List<ConsumptionFileRepository.Row> retriable = repo.findRetriableFailed(3, 100);
        assertTrue(retriable.stream().noneMatch(r -> r.sha256().equals("m1")),
                "a row marked missing must exhaust its retry budget, not just be marked failed");
    }
}
