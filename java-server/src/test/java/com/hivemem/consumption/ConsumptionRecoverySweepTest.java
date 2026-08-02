package com.hivemem.consumption;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

import java.nio.file.*;
import java.util.List;
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
        when(repo.findByFilenames(anyCollection())).thenReturn(java.util.Map.of());

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
     *  at all. The old sweep iterated ledger rows only, so it could never see this file. */
    @Test
    void orphanWithoutLedgerRowIsRestagedAndCounted() throws Exception {
        Files.writeString(tempRoot.resolve("processing").resolve("orphan.pdf"), "orphan");
        when(repo.findByFilenames(anyCollection())).thenReturn(java.util.Map.of());

        sweep.recover();

        assertTrue(Files.exists(tempRoot.resolve("orphan.pdf")),
                "an orphan without a ledger row must be moved back to the watch root");
        assertEquals(1, sweep.lastReconciliation().orphansRestaged());
    }

    /** A file left in processing/ whose row already says 'done' is an interrupted move, not work.
     *  Re-staging it would ingest it a second time; it belongs in processed/. */
    @Test
    void leftoverOfADoneRowIsMovedToProcessedNotRestaged() throws Exception {
        Files.writeString(tempRoot.resolve("processing").resolve("leftover.pdf"), "leftover");
        when(repo.findByFilenames(anyCollection())).thenReturn(java.util.Map.of(
                "leftover.pdf",
                new ConsumptionFileRepository.Row("sha-done", "leftover.pdf", "done", 1, null)));

        sweep.recover();

        assertTrue(Files.exists(tempRoot.resolve("processed").resolve("leftover.pdf")));
        assertFalse(Files.exists(tempRoot.resolve("leftover.pdf")),
                "a done leftover must NOT go back into the watch root");
        assertEquals(1, sweep.lastReconciliation().doneLeftovers());
    }

    /** A stale row whose physical file is gone is a row without data — mark it failed so it stops
     *  being selected every sweep, and count it so the operator learns about it. */
    @Test
    void staleRowWithoutFileIsMarkedFailedAndCounted() throws Exception {
        Files.delete(tempRoot.resolve("processing").resolve("stale.pdf"));
        when(repo.findByFilenames(anyCollection())).thenReturn(java.util.Map.of());

        sweep.recover();

        verify(repo).markFailed(eq("sha-stale"), contains("no physical file"));
        assertEquals(1, sweep.lastReconciliation().rowsWithoutFile());
    }
}
