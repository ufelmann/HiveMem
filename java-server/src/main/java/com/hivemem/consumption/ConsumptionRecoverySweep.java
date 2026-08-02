package com.hivemem.consumption;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Re-stages files the pipeline can no longer see: crash-stranded files in processing/ (ledger row
 *  still 'processing' past the stale threshold) and failed/ files still under the retry limit.
 *  Matching files are moved back to the watch root so the next poll re-ingests them. Content-based
 *  dedup makes re-runs safe. Runs on a schedule AND once at startup (post-restart recovery). */
@Component
@ConditionalOnProperty(name = "hivemem.consumption.enabled", havingValue = "true")
public class ConsumptionRecoverySweep implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ConsumptionRecoverySweep.class);

    private final ConsumptionProperties props;
    private final ConsumptionFileRepository repo;
    private final ConsumptionFileMover mover;
    private final Path root;

    public ConsumptionRecoverySweep(ConsumptionProperties props, ConsumptionFileRepository repo) {
        this.props = props;
        this.repo = repo;
        this.root = Path.of(props.getDir());
        this.mover = new ConsumptionFileMover(root);
    }

    /** What the last reconciliation found. Read by consumption_queue so divergences become
     *  visible instead of merely repaired — the 2026-07-19 failure was not that something broke,
     *  it was that nobody could find out. */
    public record Reconciliation(int orphansRestaged, int doneLeftovers, int rowsWithoutFile) {}

    private volatile Reconciliation last = new Reconciliation(0, 0, 0);

    public Reconciliation lastReconciliation() { return last; }

    @Override public void run(ApplicationArguments args) { recover(); }

    @Scheduled(fixedRateString = "#{@consumptionProperties.recoveryInterval.toMillis()}")
    public void recover() {
        int stale = (int) props.getRecoveryStaleThreshold().toSeconds();
        var staleRows = repo.findStaleProcessing(stale, 500);

        // Must run BEFORE reStage moves anything: only here does "no physical file in
        // processing/" genuinely mean the row has no data. Once reStage has moved a row's
        // file out, its absence means success, not divergence.
        int missing = markRowsWithoutFile(staleRows);

        for (var r : staleRows) {
            Path f = root.resolve(ConsumptionFileMover.PROCESSING).resolve(r.filename());
            reStage(f, r, "stale-processing");
        }
        for (var r : repo.findRetriableFailed(props.getFailedRetryLimit(), 500)) {
            reStage(root.resolve(ConsumptionFileMover.FAILED).resolve(r.filename()), r, "retry-failed");
        }

        Reconciliation dirReconciliation = reconcile();
        last = new Reconciliation(
                dirReconciliation.orphansRestaged(), dirReconciliation.doneLeftovers(), missing);
    }

    private void reStage(Path file, ConsumptionFileRepository.Row r, String why) {
        if (!Files.isRegularFile(file)) return; // ledger row but no physical file — skip safely
        try {
            mover.moveToRoot(file);
            repo.touch(r.sha256()); // bump updated_at so a slow-but-alive job isn't re-staged every sweep
            log.info("Recovery re-staged {} ({}, attempts={})", r.filename(), why, r.attempts());
        } catch (Exception e) {
            log.warn("Recovery could not re-stage {}: {}", r.filename(), e.toString());
        }
    }

    /** A stale row whose physical file is already gone before any move happens is a row without
     *  data — mark it failed so it stops being selected every sweep, and count it so the operator
     *  learns about it. */
    private int markRowsWithoutFile(List<ConsumptionFileRepository.Row> staleRows) {
        Path processingDir = root.resolve(ConsumptionFileMover.PROCESSING);
        int missing = 0;
        for (var r : staleRows) {
            Path f = processingDir.resolve(r.filename());
            if (!Files.isRegularFile(f)) {
                repo.markFailed(r.sha256(), "no physical file in processing/");
                missing++;
                log.warn("Reconciliation marked {} failed: row is stale and has no physical file",
                        r.filename());
            }
        }
        return missing;
    }

    /** Compares processing/ against the ledger in BOTH directions. The ledger-driven loop above
     *  cannot see a file with no row; this can. */
    private Reconciliation reconcile() {
        Path processingDir = root.resolve(ConsumptionFileMover.PROCESSING);
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(processingDir)) {
            for (Path p : s) if (Files.isRegularFile(p)) files.add(p);
        } catch (IOException e) {
            log.warn("Reconciliation could not list {}: {}", processingDir, e.toString());
            return new Reconciliation(last.orphansRestaged(), last.doneLeftovers(), last.rowsWithoutFile());
        }
        List<String> names = new ArrayList<>();
        for (Path p : files) names.add(p.getFileName().toString());
        Map<String, ConsumptionFileRepository.Row> rows = repo.findByFilenames(names);

        int orphans = 0;
        int leftovers = 0;
        for (Path p : files) {
            ConsumptionFileRepository.Row row = rows.get(p.getFileName().toString());
            try {
                if (row == null) {
                    mover.moveToRoot(p);
                    orphans++;
                    log.warn("Reconciliation re-staged {}: file in processing/ with no ledger row",
                            p.getFileName());
                } else if ("done".equals(row.state())) {
                    mover.moveToProcessed(p);
                    leftovers++;
                    log.info("Reconciliation moved {} to processed/: ledger says done",
                            p.getFileName());
                }
            } catch (IOException e) {
                log.warn("Reconciliation could not move {}: {}", p.getFileName(), e.toString());
            }
        }

        return new Reconciliation(orphans, leftovers, 0);
    }
}
