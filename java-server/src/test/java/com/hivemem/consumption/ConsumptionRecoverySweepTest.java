package com.hivemem.consumption;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

import java.nio.file.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConsumptionRecoverySweepTest {

    @TempDir Path tempRoot;

    private ConsumptionFileRepository repo;
    private ConsumptionRecoverySweep sweep;

    @BeforeEach
    void setUp() throws Exception {
        // Create subdirs
        Path processingDir = tempRoot.resolve("processing");
        Path failedDir = tempRoot.resolve("failed");
        Files.createDirectories(processingDir);
        Files.createDirectories(failedDir);

        // Place physical files
        Files.writeString(processingDir.resolve("stale.pdf"), "stale");
        Files.writeString(failedDir.resolve("retry.pdf"), "retry");
        Files.writeString(failedDir.resolve("dead.pdf"), "dead");

        // Configure properties pointing at tempRoot
        ConsumptionProperties props = new ConsumptionProperties();
        props.setDir(tempRoot.toString());
        props.setEnabled(true);
        // failedRetryLimit defaults to 3

        // Mock repository
        repo = mock(ConsumptionFileRepository.class);
        when(repo.findStaleProcessing(anyInt(), anyInt()))
                .thenReturn(List.of(new ConsumptionFileRepository.Row(
                        "sha-stale", "stale.pdf", "processing", 1, null)));
        when(repo.findRetriableFailed(eq(3), anyInt()))
                .thenReturn(List.of(new ConsumptionFileRepository.Row(
                        "sha-retry", "retry.pdf", "failed", 2, "some error")));
        // dead.pdf is NOT returned (attempts >= limit — the repo's job)
        when(repo.knownFilenames(anyCollection())).thenReturn(Set.of());

        sweep = new ConsumptionRecoverySweep(props, repo);
    }

    @Test
    void staleProcessingFileIsMovedToRoot() throws Exception {
        sweep.recover();

        Path movedFile = tempRoot.resolve("stale.pdf");
        assertTrue(Files.exists(movedFile), "stale.pdf should be in root after recovery");
        assertFalse(Files.exists(tempRoot.resolve("processing").resolve("stale.pdf")),
                "stale.pdf should no longer be in processing/");
    }

    @Test
    void retriableFailedFileIsMovedToRoot() throws Exception {
        sweep.recover();

        Path movedFile = tempRoot.resolve("retry.pdf");
        assertTrue(Files.exists(movedFile), "retry.pdf should be in root after recovery");
        assertFalse(Files.exists(tempRoot.resolve("failed").resolve("retry.pdf")),
                "retry.pdf should no longer be in failed/");
    }

    @Test
    void exhaustedFailedFileRemainsInFailed() throws Exception {
        sweep.recover();

        Path deadFile = tempRoot.resolve("failed").resolve("dead.pdf");
        assertTrue(Files.exists(deadFile), "dead.pdf should remain in failed/ (not returned by repo)");
        assertFalse(Files.exists(tempRoot.resolve("dead.pdf")),
                "dead.pdf should NOT be in root");
    }

    /** FIX 2: after re-staging a stale-processing row, touch() must be called so the sweep
     *  won't re-select the row again on the next run (duplicate-ingest prevention). */
    @Test
    void touchIsCalledAfterSuccessfulReStage() throws Exception {
        sweep.recover();

        verify(repo).touch("sha-stale");
        verify(repo).touch("sha-retry");
    }

    @Test
    void missingPhysicalFileIsSkippedGracefully() throws Exception {
        // Remove stale.pdf from disk before sweep — ledger row exists but file does not
        Files.delete(tempRoot.resolve("processing").resolve("stale.pdf"));

        // Should not throw
        assertDoesNotThrow(() -> sweep.recover());

        // retry.pdf should still be re-staged normally
        assertTrue(Files.exists(tempRoot.resolve("retry.pdf")));
    }

    /** The 2026-07-19 incident as a regression test: a file sits in processing/ with NO ledger row
     *  at all. The old sweep iterated ledger rows only, so it could never see this file. Counters
     *  are cumulative since process start: a second sweep with a fresh orphan must ADD to the
     *  total, not replace it. */
    @Test
    void orphanWithoutLedgerRowIsRestagedAndCounted() throws Exception {
        Files.writeString(tempRoot.resolve("processing").resolve("orphan.pdf"), "orphan");
        when(repo.knownFilenames(anyCollection())).thenReturn(Set.of());

        sweep.recover();

        assertTrue(Files.exists(tempRoot.resolve("orphan.pdf")),
                "an orphan without a ledger row must be moved back to the watch root");
        assertEquals(1, sweep.lastReconciliation().orphansRestaged());

        Files.writeString(tempRoot.resolve("processing").resolve("orphan2.pdf"), "orphan2");
        sweep.recover();

        assertEquals(2, sweep.lastReconciliation().orphansRestaged(),
                "counters are cumulative since process start, not reset each sweep");
    }

    /** A stale row whose physical file is gone is a row without data — mark it failed AND exhaust
     *  its retry budget (markMissing), so it stops being selected every sweep, and count it so the
     *  operator learns about it. Counters are cumulative across sweeps. */
    @Test
    void staleRowWithoutFileIsMarkedFailedAndCounted() throws Exception {
        Files.delete(tempRoot.resolve("processing").resolve("stale.pdf"));
        when(repo.knownFilenames(anyCollection())).thenReturn(Set.of());

        sweep.recover();

        verify(repo).markMissing(eq("sha-stale"), eq(3));
        assertEquals(1, sweep.lastReconciliation().rowsWithoutFile());

        sweep.recover();

        assertEquals(2, sweep.lastReconciliation().rowsWithoutFile(),
                "counters are cumulative since process start, not reset each sweep");
    }

    /** Regression: a stale-processing row that DOES have a physical file must be re-staged and
     *  must NEVER be marked missing. The missing-file check must run BEFORE the file is moved,
     *  not after — otherwise a successfully recovered row looks indistinguishable from a row
     *  that never had a file at all. */
    @Test
    void staleRowWithFileIsRestagedAndNeverMarkedFailed() throws Exception {
        sweep.recover();

        assertTrue(Files.exists(tempRoot.resolve("stale.pdf")));
        verify(repo, never()).markMissing(eq("sha-stale"), anyInt());
        assertEquals(0, sweep.lastReconciliation().rowsWithoutFile(),
                "a successfully recovered file must not appear as a divergence");

        // A second sweep with the row stale again but its file physically present must still
        // never mark it missing, and the cumulative counter must remain zero.
        Files.writeString(tempRoot.resolve("processing").resolve("stale.pdf"), "stale-again");
        sweep.recover();

        verify(repo, never()).markMissing(eq("sha-stale"), anyInt());
        assertEquals(0, sweep.lastReconciliation().rowsWithoutFile(),
                "cumulative counter stays zero when nothing new diverges");
    }

    /** C1: a 'staged' row is almost always simply QUEUED on the bounded consumption executor —
     *  nothing bumps updated_at while a task waits its turn, so a deep queue makes it look stale.
     *  Re-staging it from the SCHEDULED sweep makes the next poll submit a second task for a file
     *  that is still queued, and both then run. The scheduled sweep must leave it alone. */
    @Test
    void scheduledSweepDoesNotRestageAStagedRowStillSittingInProcessing() throws Exception {
        Files.writeString(tempRoot.resolve("processing").resolve("queued.pdf"), "queued");
        when(repo.findStaleProcessing(anyInt(), anyInt()))
                .thenReturn(List.of(new ConsumptionFileRepository.Row(
                        "sha-queued", "queued.pdf", "staged", 0, null)));
        // The row exists, so reconcile() must not treat the file as a row-less orphan either.
        when(repo.knownFilenames(anyCollection())).thenReturn(Set.of("queued.pdf"));

        sweep.recover();

        assertTrue(Files.exists(tempRoot.resolve("processing").resolve("queued.pdf")),
                "a queued 'staged' row's file must stay in processing/");
        assertFalse(Files.exists(tempRoot.resolve("queued.pdf")),
                "the scheduled sweep must not move a 'staged' row back to the watch root");
        verify(repo, never()).markMissing(eq("sha-queued"), anyInt());
        assertEquals(0, sweep.lastReconciliation().rowsWithoutFile());
    }

    /** The counterpart of the rule above: after a process death the executor queue is empty by
     *  construction, so at STARTUP a stale 'staged' row is genuinely stranded and must be recovered. */
    @Test
    void startupSweepDoesRestageTheSameStagedRow() throws Exception {
        Files.writeString(tempRoot.resolve("processing").resolve("queued.pdf"), "queued");
        when(repo.findStaleProcessing(anyInt(), anyInt()))
                .thenReturn(List.of(new ConsumptionFileRepository.Row(
                        "sha-queued", "queued.pdf", "staged", 0, null)));

        sweep.run(null);   // ApplicationRunner entry point == the startup sweep

        assertTrue(Files.exists(tempRoot.resolve("queued.pdf")),
                "the startup sweep must re-stage a stranded 'staged' row");
        assertFalse(Files.exists(tempRoot.resolve("processing").resolve("queued.pdf")));
        verify(repo).touch("sha-queued");
    }

    /** A stale 'processing' row has a live heartbeat, so staleness really does mean a dead worker:
     *  BOTH callers must re-stage it. */
    @Test
    void staleProcessingRowIsRestagedByBothCallers() throws Exception {
        sweep.recover();
        assertTrue(Files.exists(tempRoot.resolve("stale.pdf")),
                "the scheduled sweep must re-stage a stale 'processing' row");

        Files.writeString(tempRoot.resolve("processing").resolve("stale.pdf"), "stale-again");
        sweep.run(null);
        assertTrue(Files.exists(tempRoot.resolve("stale.pdf")));
        assertFalse(Files.exists(tempRoot.resolve("processing").resolve("stale.pdf")),
                "the startup sweep must re-stage a stale 'processing' row too");
    }

    /** I1: a 'staged' row's canonical location is the WATCH ROOT — the ledger row is written before
     *  the move, so a crash in between leaves the file there. Resolving it against processing/ only
     *  would call it "no physical file", set a false last_error and exhaust the retry budget for a
     *  file that is sitting right where the next poll will find it. */
    @Test
    void stagedRowWhoseFileIsStillInTheWatchRootIsNotMarkedMissing() throws Exception {
        Files.writeString(tempRoot.resolve("crashed.pdf"), "crashed-before-move");
        when(repo.findStaleProcessing(anyInt(), anyInt()))
                .thenReturn(List.of(new ConsumptionFileRepository.Row(
                        "sha-crashed", "crashed.pdf", "staged", 0, null)));

        sweep.run(null);   // startup is the caller that looks at 'staged' rows at all

        verify(repo, never()).markMissing(eq("sha-crashed"), anyInt());
        assertEquals(0, sweep.lastReconciliation().rowsWithoutFile(),
                "a file waiting in the watch root is not a divergence");
        assertTrue(Files.exists(tempRoot.resolve("crashed.pdf")),
                "the file must be left in the watch root for the next poll");
    }

    /** ...but a 'staged' row with no file in processing/ AND none in the watch root really is a row
     *  without data, and must still be marked missing. */
    @Test
    void stagedRowWithNoFileAnywhereIsStillMarkedMissing() throws Exception {
        when(repo.findStaleProcessing(anyInt(), anyInt()))
                .thenReturn(List.of(new ConsumptionFileRepository.Row(
                        "sha-vanished", "vanished.pdf", "staged", 0, null)));

        sweep.run(null);

        verify(repo).markMissing(eq("sha-vanished"), eq(3));
        assertEquals(1, sweep.lastReconciliation().rowsWithoutFile());
    }

    /** A file in processing/ whose ledger row is 'failed' is misplaced: the retry loop only looks
     *  in failed/, so it is invisible to reStage until this sweep relocates it there. Safe unlike
     *  the 'done' case — no consumption_jobs row owns a failed batch. */
    @Test
    void misplacedFailedRowIsMovedToFailedDirAndCounted() throws Exception {
        Files.writeString(tempRoot.resolve("processing").resolve("misplaced.pdf"), "misplaced");
        when(repo.knownFilenames(anyCollection())).thenReturn(Set.of("misplaced.pdf"));
        when(repo.findRetriableFailed(eq(3), anyInt())).thenReturn(List.of(
                new ConsumptionFileRepository.Row("sha-retry", "retry.pdf", "failed", 2, "some error"),
                new ConsumptionFileRepository.Row("sha-misplaced", "misplaced.pdf", "failed", 1, "boom")));

        sweep.recover();

        assertTrue(Files.exists(tempRoot.resolve("failed").resolve("misplaced.pdf")),
                "a failed row's file found in processing/ must be relocated to failed/");
        assertFalse(Files.exists(tempRoot.resolve("processing").resolve("misplaced.pdf")));
        assertEquals(1, sweep.lastReconciliation().misplacedFailed());
    }
}
