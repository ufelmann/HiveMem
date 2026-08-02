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
 *  dedup makes re-runs safe. Runs on a schedule AND once at startup (post-restart recovery) —
 *  the two runs treat 'staged' rows differently on purpose, see {@link #recover(boolean)}. */
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

    /** Startup run: the executor queue is empty by construction, so a 'staged' row here cannot be
     *  merely queued — it is genuinely stranded and must be re-staged. */
    @Override public void run(ApplicationArguments args) { recover(true); }

    /** Scheduled run: workers may be busy and the queue may be deep, so a 'staged' row is almost
     *  always simply waiting its turn. See {@link #recover(boolean)}. */
    @Scheduled(fixedRateString = "#{@consumptionProperties.recoveryInterval.toMillis()}")
    public void recover() { recover(false); }

    /**
     * @param startup {@code true} only for the {@link ApplicationRunner} run at boot.
     *
     * <p>The two callers deliberately treat {@code staged} rows differently, and the asymmetry is
     * not arbitrary:
     *
     * <ul>
     *   <li>A {@code processing} row has a live heartbeat ({@code ConsumptionService} and
     *       {@code ReassemblyOrchestrator} bump {@code updated_at} once per page), so a stale one
     *       really does mean a dead worker. Both callers re-stage it.
     *   <li>A {@code staged} row means "registered, not yet started". During normal operation it is
     *       almost always just QUEUED on the bounded consumption executor: nothing bumps
     *       {@code updated_at} while a task merely waits, and a deep queue can take longer to drain
     *       than the stale threshold. Re-staging such a row makes the next poll submit a SECOND task
     *       for the same file — {@code processStaged} has no idempotency guard, so both would run,
     *       double the vision cost, and let the loser's failed move degrade the whole batch into one
     *       extra {@code pending} document on top of the correctly split ones. The scheduled sweep
     *       therefore leaves {@code staged} rows alone.
     *   <li>The one case where a {@code staged} row IS stranded is a process death between
     *       {@code stage()} and dispatch. After a restart the executor queue is empty, so at startup
     *       "stale and staged" is unambiguous — hence the startup run does re-stage them.
     * </ul>
     */
    void recover(boolean startup) {
        if (!running.compareAndSet(false, true)) {
            log.debug("Recovery sweep already running; skipping this trigger");
            return;
        }
        try {
            int stale = (int) props.getRecoveryStaleThreshold().toSeconds();
            var staleRows = repo.findStaleProcessing(stale, 500);
            if (!startup) {
                staleRows = staleRows.stream()
                        .filter(r -> !"staged".equals(r.state()))
                        .toList();
            }

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
     *  sweep, and count it so the operator learns about it.
     *
     *  <p>Which directory counts as "has a file" depends on the row's state. A {@code processing}
     *  row's file lives in {@code processing/}. A {@code staged} row's canonical location is the
     *  WATCH ROOT: {@code ConsumptionWatcher} writes the ledger row BEFORE moving the file out, so a
     *  crash in between — the very scenario staging exists to survive — leaves the file exactly
     *  where the poll found it. Resolving such a row against {@code processing/} only would call it
     *  "no physical file", set a false {@code last_error}, permanently exhaust its retry budget and
     *  count a non-divergence. */
    private int markRowsWithoutFile(List<ConsumptionFileRepository.Row> staleRows) {
        Path processingDir = root.resolve(ConsumptionFileMover.PROCESSING);
        int missing = 0;
        for (var r : staleRows) {
            boolean present = Files.isRegularFile(processingDir.resolve(r.filename()))
                    || ("staged".equals(r.state()) && Files.isRegularFile(root.resolve(r.filename())));
            if (!present) {
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
