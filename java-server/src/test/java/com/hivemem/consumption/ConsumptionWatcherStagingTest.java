package com.hivemem.consumption;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConsumptionWatcherStagingTest {

    @TempDir Path root;

    /** The ledger row must exist BEFORE the file leaves the watch root. Otherwise a crash in
     *  between strands the file in processing/ with no row, and ConsumptionRecoverySweep — which
     *  iterates ledger rows only — can never see it again (real incident, 2026-07-19). */
    @Test
    void ledgerRowIsWrittenBeforeTheFileMoves() throws Exception {
        Path scan = root.resolve("scan.pdf");
        Files.writeString(scan, "synthetic-pdf-bytes");

        ConsumptionProperties props = new ConsumptionProperties();
        props.setDir(root.toString());
        props.setEnabled(true);
        props.setStableSeconds(0);

        ConsumptionService service = mock(ConsumptionService.class);
        ConsumptionFileRepository repo = mock(ConsumptionFileRepository.class);
        Executor inline = Runnable::run;

        // Records where the file was at the moment stage() was called.
        final boolean[] stillInRoot = {false};
        doAnswer(inv -> {
            stillInRoot[0] = Files.exists(scan);
            return null;
        }).when(repo).stage(anyString(), anyString());

        // Fixed clock far in the future so (now - mtime) >= stableMillis always holds
        // (same pattern as ConsumptionWatcherDedupeIT — an absolute instant would go stale
        // as soon as wall-clock time passes it).
        Clock clock = Clock.fixed(Instant.now().plusSeconds(3600), ZoneOffset.UTC);
        ConsumptionWatcher watcher = new ConsumptionWatcher(props, service, inline, clock, repo);

        watcher.poll();   // first poll registers the file with the stability detector
        watcher.poll();   // second poll sees it as stable and stages it

        verify(repo).stage(anyString(), eq("scan.pdf"));
        assertTrue(stillInRoot[0],
                "stage() must be called while the file is still in the watch root");
    }

    /** The hash the watcher registers must be the one the service later marks done, or markDone
     *  updates a row that does not exist and the file stays 'staged' forever. Asserted against the
     *  independently-computed SHA-256 of the synthetic content, not just mutual agreement — two
     *  values that only agree with each other could both be a hardcoded constant. */
    @Test
    void watcherPassesTheSameHashItRegistered() throws Exception {
        byte[] content = "synthetic-pdf-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path scan = root.resolve("scan.pdf");
        Files.write(scan, content);
        String expectedHash = ConsumptionService.sha256(content);

        ConsumptionProperties props = new ConsumptionProperties();
        props.setDir(root.toString());
        props.setEnabled(true);
        props.setStableSeconds(0);

        ConsumptionService service = mock(ConsumptionService.class);
        ConsumptionFileRepository repo = mock(ConsumptionFileRepository.class);
        // Fixed clock far in the future so (now - mtime) >= stableMillis always holds
        // (same pattern as ConsumptionWatcherDedupeIT).
        Clock clock = Clock.fixed(Instant.now().plusSeconds(3600), ZoneOffset.UTC);
        ConsumptionWatcher watcher =
                new ConsumptionWatcher(props, service, (Runnable r) -> r.run(), clock, repo);

        watcher.poll();
        watcher.poll();

        verify(repo).stage(eq(expectedHash), anyString());
        verify(service).processStaged(any(Path.class), eq(expectedHash));
    }

    /** The point of writing the ledger row first: if anything goes wrong between the insert and the
     *  move, the file must still be sitting in the watch root — never half-way to processing/ with
     *  no row, and never lost. A later poll then ingests it normally.
     *
     *  <p>The failure is simulated by making {@code processing/} a regular FILE, so
     *  {@code Files.createDirectories} inside the mover throws exactly where a crash would have hit. */
    @Test
    void aFailureBetweenInsertAndMoveLeavesTheFileInTheWatchRootForTheNextPoll() throws Exception {
        byte[] content = "synthetic-pdf-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path scan = root.resolve("scan.pdf");
        Files.write(scan, content);
        String expectedHash = ConsumptionService.sha256(content);

        // Blocks the move: the mover cannot create processing/ because a file already occupies it.
        Path blocker = root.resolve("processing");
        Files.writeString(blocker, "not a directory");

        ConsumptionProperties props = new ConsumptionProperties();
        props.setDir(root.toString());
        props.setEnabled(true);
        props.setStableSeconds(0);

        ConsumptionService service = mock(ConsumptionService.class);
        ConsumptionFileRepository repo = mock(ConsumptionFileRepository.class);
        Clock clock = Clock.fixed(Instant.now().plusSeconds(3600), ZoneOffset.UTC);
        ConsumptionWatcher watcher =
                new ConsumptionWatcher(props, service, (Runnable r) -> r.run(), clock, repo);

        watcher.poll();
        watcher.poll();   // stages the row, then fails to move

        verify(repo).stage(eq(expectedHash), eq("scan.pdf"));
        verify(service, never()).processStaged(any(Path.class), anyString());
        assertTrue(Files.exists(scan),
                "a failure after the ledger insert must leave the file in the watch root");

        // Recovery: the obstruction is gone, so the next stable poll ingests the file normally.
        Files.delete(blocker);
        watcher.poll();
        watcher.poll();

        verify(service).processStaged(any(Path.class), eq(expectedHash));
        assertFalse(Files.exists(scan), "the retried poll must move the file out of the watch root");
        assertTrue(Files.isRegularFile(root.resolve("processing").resolve("scan.pdf")));
    }
}
