package com.hivemem.consumption;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the fix for the critical defect found in review: the original handler called
 * {@code repo.stage(...)} without moving the physical file, which (a) did nothing to make the
 * pipeline see the file again and (b) actively broke the retry path — the row leaves 'failed', so
 * {@code findRetriableFailed} stops selecting it; once stale, {@code findStaleProcessing} picks it
 * up as 'staged'; the missing-file check finds nothing in processing/; {@code markMissing} then
 * exhausts the retry budget and overwrites {@code last_error}. The fix mirrors
 * {@link ConsumptionRecoverySweep#recover} exactly: move the physical file back to the watch root
 * and let the next poll's {@code ConsumptionWatcher} call {@code stage()} itself.
 */
class ConsumptionRetryServiceTest {

    @TempDir Path tempRoot;

    private ConsumptionFileRepository repo;
    private ConsumptionRetryService service;

    @BeforeEach
    void setUp() {
        ConsumptionProperties props = new ConsumptionProperties();
        props.setDir(tempRoot.toString());
        props.setEnabled(true);

        repo = mock(ConsumptionFileRepository.class);
        service = new ConsumptionRetryService(repo, props);
    }

    @Test
    void unknownHashIsRejectedWithoutTouchingAnything() {
        when(repo.findByHash("unknown-sha")).thenReturn(Optional.empty());

        var result = service.retry("unknown-sha");

        assertFalse(result.restaged());
        assertEquals("unknown sha256", result.error());
        verify(repo, never()).stage(any(), any());
    }

    @Test
    void fileInFailedDirIsMovedToRootAndRowIsNotTouched() throws Exception {
        Files.createDirectories(tempRoot.resolve("failed"));
        Files.writeString(tempRoot.resolve("failed").resolve("scan.pdf"), "content");
        when(repo.findByHash("sha-failed")).thenReturn(Optional.of(
                new ConsumptionFileRepository.Row("sha-failed", "scan.pdf", "failed", 3, "boom")));

        var result = service.retry("sha-failed");

        assertTrue(result.restaged());
        assertTrue(Files.exists(tempRoot.resolve("scan.pdf")),
                "file must be moved to the watch root");
        assertFalse(Files.exists(tempRoot.resolve("failed").resolve("scan.pdf")));
        verify(repo, never()).stage(any(), any());
    }

    @Test
    void fileInProcessingDirIsMovedToRootAndRowIsNotTouched() throws Exception {
        Files.createDirectories(tempRoot.resolve("processing"));
        Files.writeString(tempRoot.resolve("processing").resolve("scan.pdf"), "content");
        when(repo.findByHash("sha-processing")).thenReturn(Optional.of(
                new ConsumptionFileRepository.Row("sha-processing", "scan.pdf", "processing", 1, null)));

        var result = service.retry("sha-processing");

        assertTrue(result.restaged());
        assertTrue(Files.exists(tempRoot.resolve("scan.pdf")),
                "file must be moved to the watch root");
        assertFalse(Files.exists(tempRoot.resolve("processing").resolve("scan.pdf")));
        verify(repo, never()).stage(any(), any());
    }

    @Test
    void fileInProcessedDirIsMovedToRootAndRowIsNotTouched() throws Exception {
        // Degraded batches (findDegradedBatches has no state filter) completed normally, so their
        // file lives in processed/, not failed/ or processing/. The queen-route retry button must
        // still work for them.
        Files.createDirectories(tempRoot.resolve("processed"));
        Files.writeString(tempRoot.resolve("processed").resolve("scan.pdf"), "content");
        when(repo.findByHash("sha-processed")).thenReturn(Optional.of(
                new ConsumptionFileRepository.Row("sha-processed", "scan.pdf", "done", 0, null)));

        var result = service.retry("sha-processed");

        assertTrue(result.restaged());
        assertTrue(Files.exists(tempRoot.resolve("scan.pdf")),
                "file must be moved to the watch root");
        assertFalse(Files.exists(tempRoot.resolve("processed").resolve("scan.pdf")));
        verify(repo, never()).stage(any(), any());
    }

    @Test
    void noPhysicalFileAnywhereLeavesTheRowUntouched() {
        when(repo.findByHash("sha-gone")).thenReturn(Optional.of(
                new ConsumptionFileRepository.Row("sha-gone", "gone.pdf", "failed", 3, "boom")));

        var result = service.retry("sha-gone");

        assertFalse(result.restaged());
        assertNotNull(result.error());
        verify(repo, never()).stage(any(), any());
    }
}
