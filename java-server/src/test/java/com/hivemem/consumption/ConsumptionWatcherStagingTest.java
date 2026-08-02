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
     *  updates a row that does not exist and the file stays 'staged' forever. */
    @Test
    void watcherPassesTheSameHashItRegistered() throws Exception {
        Path scan = root.resolve("scan.pdf");
        Files.writeString(scan, "synthetic-pdf-bytes");

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

        var stagedHash = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repo).stage(stagedHash.capture(), anyString());
        verify(service).processStaged(any(Path.class), eq(stagedHash.getValue()));
    }
}
