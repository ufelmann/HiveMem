package com.hivemem.contradiction;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Recovers what {@link ContradictionSweep} dispatches but a webhook never closes out, and closes
 * pairs a human will never get to review. This is the mechanism that lets the whole design promise
 * every dispatched item eventually reaches a terminal status.
 *
 * <p>Two independent failure modes, one sweep:
 *
 * <ol>
 *   <li>A dispatched job's webhook callback never arrives (the Vistierie run died, the network ate
 *       it, or the dispatch response's run id was never attached). {@link
 *       ContradictionJobRepository#findStale} finds it (covering both {@code awaiting} and {@code
 *       processing} — see its Javadoc for why both), {@link
 *       ContradictionJobRepository#reclaimStale} atomically takes ownership, and the matching
 *       repository's {@code applyAttemptRule} resolves its still-{@code in_flight} rows before the
 *       job itself is marked {@code failed}.
 *   <li>A {@code pending} pair's underlying fact stopped being active (invalidated or revised)
 *       while it sat awaiting human review. {@link ContradictionRepository#autoCloseInactive}
 *       closes it as {@code superseded} — run once per tick, after the per-job loop, since it acts
 *       on {@code pending} rows entirely unrelated to whichever jobs happened to go stale this
 *       tick.
 * </ol>
 *
 * <p><b>The {@code processing} recovery gap, and why {@link
 * ContradictionJobRepository#reclaimStale} closes it:</b> {@link ContradictionJobRepository#claim}
 * only matches {@code status='awaiting'}. A job that reached {@code processing} — via a claim by
 * this very sweep on an earlier tick, or eventually a webhook, that then crashed before a terminal
 * write — would fail {@code claim()} forever, and {@code findStale} would keep re-offering the same
 * unrecoverable row on every tick. {@code reclaimStale} takes both {@code awaiting} and {@code
 * processing} rows in one atomic UPDATE, re-checking staleness at write time rather than trusting
 * the earlier {@code findStale} snapshot — see its Javadoc for why that is safe against a webhook
 * that is still genuinely working the row.
 */
@Component
@ConditionalOnProperty(name = "hivemem.contradiction.enabled", havingValue = "true")
public class ContradictionReconcileSweep {

    private static final Logger log = LoggerFactory.getLogger(ContradictionReconcileSweep.class);
    private static final int BATCH = 10;

    private final ContradictionProperties props;
    private final ContradictionJobRepository jobs;
    private final ContradictionRepository pairs;
    private final PredicateCardinalityRepository cardinality;

    public ContradictionReconcileSweep(
            ContradictionProperties props,
            ContradictionJobRepository jobs,
            ContradictionRepository pairs,
            PredicateCardinalityRepository cardinality) {
        this.props = props;
        this.jobs = jobs;
        this.pairs = pairs;
        this.cardinality = cardinality;
    }

    /**
     * {@code initialDelayString} defers the first tick by one full interval after startup, for the
     * same reason as {@link ContradictionSweep#tick}: an immediate tick on context startup would
     * race an integration test's own fixture setup.
     */
    @Scheduled(
            fixedRateString = "#{@contradictionProperties.reconcileInterval.toMillis()}",
            initialDelayString = "#{@contradictionProperties.reconcileInterval.toMillis()}")
    public void reconcile() {
        List<ContradictionJobRepository.Job> stale = jobs.findStale(props.getStaleThreshold(), BATCH);
        for (ContradictionJobRepository.Job job : stale) {
            // One bad job must not take out the rest of this tick's stale jobs, nor the
            // autoCloseInactive() call below - mirrors SeparationReconcileSweep.degrade's own
            // per-job isolation, for the same reason: an exception here is a single job's problem,
            // not the whole sweep's.
            try {
                recover(job);
            } catch (Exception e) {
                log.error("Reconcile failed for {} job {}: {}", job.kind(), job.id(), e.toString());
            }
        }

        int closed = pairs.autoCloseInactive();
        if (closed > 0) {
            log.info("Auto-closed {} pending pair(s) whose fact became inactive", closed);
        }
    }

    /**
     * Claim ({@link ContradictionJobRepository#reclaimStale}) before doing any work: a webhook
     * completing this exact job around the same time must not race the attempt rule and the
     * terminal write applied here. If the claim fails, someone else already owns (or already
     * closed) this job — skip it silently rather than fighting over it.
     *
     * <p>Package-private for tests, mirroring {@code SeparationReconcileSweep#degrade}: it lets a
     * test hand this method a {@link ContradictionJobRepository.Job} snapshot taken <em>before</em>
     * a simulated concurrent winner claims the row, deterministically exercising the guard without
     * relying on real thread-timing races.
     */
    void recover(ContradictionJobRepository.Job job) {
        if (!jobs.reclaimStale(job.id(), props.getStaleThreshold())) {
            log.info("Contradiction job {} ({}) no longer stale/claimable; skipping reconcile",
                    job.id(), job.kind());
            return;
        }

        if ("cardinality".equals(job.kind())) {
            cardinality.applyAttemptRule(job.id(), props.getMaxAttempts());
        } else {
            pairs.applyAttemptRule(job.id(), props.getMaxAttempts());
        }

        // markFailed is conditional (WHERE status IN ('awaiting','processing')) because this sweep
        // is not the only writer that can reach a terminal state for this job: after reclaimStale
        // re-established 'processing', a slow webhook's markDone (itself conditional on
        // 'processing') can still land between the applyAttemptRule call above and this write. If
        // that happens, this caller lost the terminal race - the job is legitimately 'done', and
        // warning that it was "marked failed" would be a lie. The rows are still recovered
        // regardless (they are now 'retryable'/'deferred' and visible to findRetryable /
        // findUnjudged), so nothing is corrupted; only the log line must reflect who actually won.
        if (!jobs.markFailed(job.id())) {
            log.warn("Contradiction {} job {} (run {}) reached a terminal state via another writer "
                    + "before this sweep's markFailed; its in-flight items still follow the attempt rule",
                    job.kind(), job.id(), job.runId());
            return;
        }
        log.warn("Reconciled stale {} job {} (run {}): marked failed, its in-flight items follow the attempt rule",
                job.kind(), job.id(), job.runId());
    }
}
