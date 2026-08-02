package com.hivemem.consumption;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "hivemem.consumption.enabled", havingValue = "true")
public class ConsumptionWatcher {

    private static final Logger log = LoggerFactory.getLogger(ConsumptionWatcher.class);

    private final ConsumptionProperties props;
    private final ConsumptionService service;
    private final Executor executor;
    private final ConsumptionFileMover mover;
    private final StableFileDetector detector;
    private final Clock clock;
    private final ConsumptionFileRepository fileRepo;   // may be null (ledger disabled)

    @Autowired
    public ConsumptionWatcher(ConsumptionProperties props, ConsumptionService service,
                              @Qualifier("consumptionExecutor") Executor executor,
                              ObjectProvider<ConsumptionFileRepository> fileRepoProvider) {
        this(props, service, executor, Clock.systemUTC(), fileRepoProvider.getIfAvailable());
    }

    ConsumptionWatcher(ConsumptionProperties props, ConsumptionService service,
                       Executor executor, Clock clock) {
        this(props, service, executor, clock, null);
    }

    ConsumptionWatcher(ConsumptionProperties props, ConsumptionService service,
                       Executor executor, Clock clock, ConsumptionFileRepository fileRepo) {
        this.props = props;
        this.service = service;
        this.executor = executor;
        this.mover = new ConsumptionFileMover(Path.of(props.getDir()));
        this.detector = new StableFileDetector(props.getStableSeconds());
        this.clock = clock;
        this.fileRepo = fileRepo;
    }

    @Scheduled(fixedRateString = "#{@consumptionProperties.pollInterval.toMillis()}")
    public void poll() {
        Path dir = Path.of(props.getDir());
        if (!Files.isDirectory(dir)) return;
        long now = clock.millis();
        Set<Path> present = new HashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                try {
                    if (!Files.isRegularFile(p)) continue;            // skips processed/ failed/ subdirs
                    String name = p.getFileName().toString();
                    if (name.startsWith(".")) continue;               // dotfiles / partial uploads
                    present.add(p);
                    BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                    if (detector.isStable(p, a.size(), a.lastModifiedTime().toMillis(), now)) {
                        detector.forget(p);
                        // Stage out of the watch root BEFORE processing so the next poll cannot re-submit
                        // it (exactly-once), then hand off to the bounded executor — the poll thread never
                        // blocks on OCR/dispatch.
                        try {
                            // Hash and register BEFORE the move. The extra read costs ~40 ms for a
                            // 40 MB file; a death after the move but before the row exists would
                            // otherwise strand the file where no recovery path can find it.
                            String sha256 = ConsumptionService.sha256(Files.readAllBytes(p));
                            if (fileRepo != null) fileRepo.stage(sha256, p.getFileName().toString());
                            Path staged = mover.moveToProcessing(p);
                            executor.execute(() -> service.processStaged(staged, sha256));
                        } catch (Exception stageErr) {
                            // Catches IOException (read/move) AND unchecked DataAccessException from
                            // fileRepo.stage(): a DB blip must cost only this file, not silently abort
                            // the rest of the scan (the file is still in the watch root, unmoved).
                            log.warn("Could not stage {} to processing/: {} (will retry next poll)",
                                    p.getFileName(), stageErr.toString());
                        }
                    }
                } catch (IOException fileErr) {
                    // One vanished/unreadable file (e.g. deleted between listing and readAttributes)
                    // must not abort the whole scan — the remaining files still get processed.
                    log.warn("Consumption poll skipped {}: {}", p.getFileName(), fileErr.toString());
                }
            }
            // Scan completed: prune detector state for files removed externally before they ever
            // became stable, so the tracking map cannot grow without bound.
            detector.retainOnly(present);
        } catch (IOException e) {
            log.warn("Consumption poll failed for {}: {}", dir, e.toString());
        }
    }
}
