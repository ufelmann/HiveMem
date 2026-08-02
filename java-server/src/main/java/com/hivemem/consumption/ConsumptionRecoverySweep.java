package com.hivemem.consumption;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /** What reconciliation has found since process start. Cumulative, not a snapshot of the last
     *  sweep: a divergence found once must stay visible to the review queue that reads this, not
     *  disappear the moment the next sweep finds nothing new. Read by consumption_queue so
     *  divergences become visible instead of merely repaired — the 2026-07-19 failure was not that
     *  something broke, it was that nobody could find out. */
    public record Reconciliation(int orphansRestaged, int rowsWithoutFile, int misplacedFailed) {}

    private volatile Reconciliation last = new Reconciliation(0, 0, 0);

    /** Guards against overlapping sweeps: @Scheduled and the startup ApplicationRunner can both
     *  fire recover() close together. A second concurrent run is a no-op, not a race on `last`. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ConsumptionRecoverySweep(ConsumptionProperties props, ConsumptionFileRepository repo) {
        this.props = props;
        this.repo = repo;
        this.root = Path.of(props.getDir());
        this.mover = new ConsumptionFileMover(root);
    }

    public Reconciliation lastReconciliation() { return last; }

    @Override public void run(ApplicationArguments args) { recover(); }

    @Scheduled(fixedRateString = "#{@consumptionProperties.recoveryInterval.toMillis()}")
    public void recover() {
        if (!running.compareAndSet(false, true)) {
            log.debug("Recovery sweep already running; skipping this trigger");
            return;
        }
        try {
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
            var retriableFailed = repo.findRetriableFailed(props.getFailedRetryLimit(), 500);
            for (var r : retriableFailed) {
                reStage(root.resolve(ConsumptionFileMover.FAILED).resolve(r.filename()), r, "retry-failed");
            }

            Set<String> retriableFailedFilenames = new HashSet<>();
            for (var r : retriableFailed) retriableFailedFilenames.add(r.filename());

            Reconciliation delta = reconcile(retriableFailedFilenames);
            last = new Reconciliation(
                    last.orphansRestaged() + delta.orphansRestaged(),
                    last.rowsWithoutFile() + missing,
                    last.misplacedFailed() + delta.misplacedFailed());
        } finally {
            running.set(false);
        }
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
     *  data — mark it missing (failed + retry budget exhausted) so it stops being selected every
     *  sweep, and count it so the operator learns about it. */
    private int markRowsWithoutFile(List<ConsumptionFileRepository.Row> staleRows) {
        Path processingDir = root.resolve(ConsumptionFileMover.PROCESSING);
        int missing = 0;
        for (var r : staleRows) {
            Path f = processingDir.resolve(r.filename());
            if (!Files.isRegularFile(f)) {
                repo.markMissing(r.sha256(), props.getFailedRetryLimit());
                missing++;
                log.warn("Reconciliation marked {} failed: row is stale and has no physical file",
                        r.filename());
            }
        }
        return missing;
    }

    /** Compares processing/ against the ledger in BOTH directions. The ledger-driven loops above
     *  cannot see a file with no row (or one filed under the wrong state); this can. Returns a
     *  DELTA for this sweep only — the caller accumulates it into the cumulative total. */
    private Reconciliation reconcile(Set<String> retriableFailedFilenames) {
        Path processingDir = root.resolve(ConsumptionFileMover.PROCESSING);
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(processingDir)) {
            for (Path p : s) if (Files.isRegularFile(p)) files.add(p);
        } catch (IOException e) {
            log.warn("Reconciliation could not list {}: {}", processingDir, e.toString());
            return new Reconciliation(0, 0, 0);
        }
        List<String> names = new ArrayList<>();
        for (Path p : files) names.add(p.getFileName().toString());
        Set<String> known = repo.knownFilenames(names);

        int orphans = 0;
        int misplacedFailed = 0;
        for (Path p : files) {
            String name = p.getFileName().toString();
            try {
                if (!known.contains(name)) {
                    mover.moveToRoot(p);
                    orphans++;
                    log.warn("Reconciliation re-staged {}: file in processing/ with no ledger row", name);
                } else if (retriableFailedFilenames.contains(name)) {
                    // A row in state 'failed' whose file sits in processing/ is misplaced: the
                    // retry loop above only looks in failed/, so it is invisible to reStage until
                    // relocated here. Safe unlike the 'done' case below — no consumption_jobs row
                    // owns a failed batch.
                    mover.moveToFailed(p);
                    misplacedFailed++;
                    log.info("Reconciliation moved {} to failed/: ledger row is failed but file "
                            + "was left in processing/", name);
                }
                // Deliberately NOT handled: a row in state 'done' with its file still in
                // processing/. ConsumptionService.separateStaged marks the ledger row 'done' at
                // dispatch time and intentionally leaves the file in processing/ — ownership
                // passes to consumption_jobs + this sweep's degrade() path until the webhook
                // completes, and apply()'s failure path re-reads the file to recompute its hash.
                // Moving it (to processed/ or anywhere else) would break that handoff and would
                // fire on every routine multi-page scan, not just on a divergence. Do not re-add
                // a 'done' branch here — see ConsumptionService.separateStaged.
            } catch (IOException e) {
                log.warn("Reconciliation could not move {}: {}", name, e.toString());
            }
        }

        return new Reconciliation(orphans, 0, misplacedFailed);
    }
}
