package com.hivemem.contradiction;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The single scheduled sweep that drives contradiction detection: one dispatched run per tick,
 * Stage A (predicate cardinality) taking precedence over Stage B (candidate pairs) whenever any
 * predicate still lacks a verdict.
 *
 * <p>Ordering mirrors {@code ConsumptionService.separateStaged} exactly, for the same reason: the
 * HTTP call to Vistierie must never sit inside the write transaction (see {@code
 * WriteToolService#addCell}'s Javadoc for why — a pooled connection, or here an advisory lock, must
 * never be held for the duration of a network round trip). So every tick runs in two phases:
 *
 * <ol>
 *   <li>One transaction: acquire the daily-ceiling advisory lock, check {@link
 *       ContradictionJobRepository#countToday()} against {@link
 *       ContradictionProperties#getMaxRunsPerDay()}, create the job row ({@code awaiting}, no run
 *       id yet), reserve its items. Commit.
 *   <li>Outside any transaction: call the matching Vistierie client's {@code dispatch}. On success,
 *       attach the returned run id. On a stop signal ({@link DispatchRejectedException}), compensate
 *       the reservations and delete the job — net effect: as if the tick never ran, including the
 *       daily counter. On any other failure (timeout, connect-refused, 5xx), leave the job {@code
 *       awaiting} with no run id for the reconcile sweep to recover; nothing is rolled back, because
 *       the run may have started on Vistierie's side.
 * </ol>
 *
 * <p>The daily ceiling is a check-then-act ({@code countToday()} then an INSERT), and {@code
 * task.scheduling.pool.size} is 4 — without a lock, two concurrent ticks could both pass the
 * ceiling check before either commits. {@code pg_advisory_xact_lock} (precedent: {@code
 * WriteToolRepository#advisoryXactLock}, {@code WriteToolRepository#updateBlueprint}) serializes the
 * check against the insert across concurrent ticks, and is released automatically at commit.
 */
@Component
@ConditionalOnProperty(name = "hivemem.contradiction.enabled", havingValue = "true")
public class ContradictionSweep {

    private static final Logger log = LoggerFactory.getLogger(ContradictionSweep.class);

    /** Arbitrary key hashed into the advisory lock id; must be stable across the whole sweep. */
    private static final String CEILING_LOCK_KEY = "contradiction-sweep-ceiling";

    /**
     * A persistent stop signal (Vistierie paused/quota-exhausted/misconfigured) would otherwise be
     * an infinite, silent loop: every tick reserves, gets rejected, compensates, and repeats,
     * burning no attempts by design (compensation is not a failed attempt). Once the number of
     * consecutive stop signals crosses this threshold, warn loudly that the sweep is making no
     * progress rather than let it run forever unnoticed.
     */
    private static final int STOP_SIGNAL_WARN_THRESHOLD = 3;

    private final DSLContext dsl;
    private final ContradictionProperties props;
    private final ContradictionJobRepository jobs;
    private final PredicateCardinalityRepository cardinality;
    private final ContradictionCandidateRepository candidates;
    private final ContradictionRepository pairs;
    private final VistierieCardinalityClient cardinalityClient;
    private final VistierieContradictionClient pairsClient;

    private final AtomicInteger consecutiveStopSignals = new AtomicInteger();

    public ContradictionSweep(
            DSLContext dsl,
            ContradictionProperties props,
            ContradictionJobRepository jobs,
            PredicateCardinalityRepository cardinality,
            ContradictionCandidateRepository candidates,
            ContradictionRepository pairs,
            VistierieCardinalityClient cardinalityClient,
            VistierieContradictionClient pairsClient) {
        this.dsl = dsl;
        this.props = props;
        this.jobs = jobs;
        this.cardinality = cardinality;
        this.candidates = candidates;
        this.pairs = pairs;
        this.cardinalityClient = cardinalityClient;
        this.pairsClient = pairsClient;
    }

    /**
     * {@code initialDelayString} defers the first real tick by one full interval after startup:
     * without it, one tick fires immediately on a scheduler thread as soon as the context is up,
     * which races an integration test's own fixture setup in principle (it lands on empty tables
     * today, so it is silent, but it is exactly the kind of assumption that flakes years later once
     * a test happens to seed data before the container is fully up).
     */
    @Scheduled(
            fixedRateString = "#{@contradictionProperties.sweepInterval.toMillis()}",
            initialDelayString = "#{@contradictionProperties.sweepInterval.toMillis()}")
    public void tick() {
        UUID correlationId = UUID.randomUUID();

        StageAJob stageA = reserveStageA(correlationId);
        if (stageA != null) {
            dispatchStageA(correlationId, stageA);
            return;
        }

        StageBJob stageB = reserveStageB(correlationId);
        if (stageB != null) {
            dispatchStageB(correlationId, stageB);
        }
    }

    /**
     * @return the reserved Stage-A job, or {@code null} if the daily ceiling was reached or no
     *     predicate needs asking (in which case the caller falls through to Stage B)
     */
    private StageAJob reserveStageA(UUID correlationId) {
        return dsl.transactionResult(configuration -> {
            DSLContext tx = DSL.using(configuration);
            tx.execute("SELECT pg_advisory_xact_lock(hashtext(?))", CEILING_LOCK_KEY);
            if (jobs.countToday() >= props.getMaxRunsPerDay()) {
                return null;
            }

            int cap = props.getCardinalityBatchSize();
            List<String> retryable = cardinality.findRetryable(cap);
            int remaining = cap - retryable.size();
            List<String> unjudged = remaining > 0 ? cardinality.findUnjudged(remaining) : List.of();
            List<String> merged = new ArrayList<>(retryable);
            merged.addAll(unjudged);
            if (merged.isEmpty()) {
                return null;
            }

            UUID jobId = jobs.create(correlationId, "cardinality", merged.size());
            cardinality.reReserve(retryable, jobId);
            cardinality.reserve(unjudged, jobId);
            return new StageAJob(jobId, merged);
        });
    }

    /**
     * @return the reserved Stage-B job, or {@code null} if the daily ceiling was reached or the
     *     candidate pool was empty (the job was created, found empty, and deleted again inside the
     *     same transaction, so an empty pool never consumes a daily slot)
     */
    private StageBJob reserveStageB(UUID correlationId) {
        return dsl.transactionResult(configuration -> {
            DSLContext tx = DSL.using(configuration);
            tx.execute("SELECT pg_advisory_xact_lock(hashtext(?))", CEILING_LOCK_KEY);
            if (jobs.countToday() >= props.getMaxRunsPerDay()) {
                return null;
            }

            UUID jobId = jobs.create(correlationId, "pairs", 0);
            List<UUID> reReserved = pairs.reReserve(jobId, props.getBatchSize());
            int remaining = props.getBatchSize() - reReserved.size();
            List<ContradictionCandidate> topUp = remaining > 0
                    ? candidates.findUnjudged(remaining, props.getMaxPairsPerGroup())
                    : List.of();
            List<UUID> inserted = topUp.isEmpty() ? List.of() : pairs.reserve(topUp, jobId);

            int total = reReserved.size() + inserted.size();
            if (total == 0) {
                jobs.delete(jobId);
                return null;
            }
            jobs.updateItemCount(jobId, total);
            return new StageBJob(jobId);
        });
    }

    private void dispatchStageA(UUID correlationId, StageAJob job) {
        List<PredicatePayload> payload = new ArrayList<>();
        for (String predicate : job.predicates()) {
            List<String> samples =
                    cardinality.sampleObjectsForLargestGroup(predicate, props.getCardinalitySamples());
            payload.add(new PredicatePayload(predicate, samples));
        }
        settleDispatch(job.jobId(), "cardinality", payload.size(), "predicates",
                () -> cardinalityClient.dispatch(correlationId, payload),
                () -> cardinality.compensate(job.jobId()));
    }

    private void dispatchStageB(UUID correlationId, StageBJob job) {
        List<PairPayload> payload = new ArrayList<>();
        for (ContradictionRepository.PairForPayload row : pairs.inFlightPayloadRowsOfJob(job.jobId())) {
            payload.add(new PairPayload(row.id(), row.subject(), row.predicate(), row.objectA(), row.objectB()));
        }
        settleDispatch(job.jobId(), "pairs", payload.size(), "pairs",
                () -> pairsClient.dispatch(correlationId, payload),
                () -> pairs.compensate(job.jobId()));
    }

    /**
     * Shared success/failure handling for both stages' dispatch call, kept in one place so the two
     * stages cannot drift out of sync on this logic (both had near-identical bodies before this was
     * extracted).
     *
     * <p>{@code compensate} and {@link ContradictionJobRepository#delete} are wrapped in a single
     * {@code dsl.transaction(...)}, not called as two independent statements: {@code compensate} is
     * itself {@code @Transactional} (its own {@code REQUIRED} propagation joins the outer
     * transaction cleanly), but without an outer transaction a crash between the two calls would
     * strand an empty {@code awaiting} job — {@link ContradictionJobRepository#countToday()} counts
     * rows regardless of status, so that stranded row would silently consume a daily slot until UTC
     * midnight, violating this whole path's "as if the tick never ran" contract in exactly the
     * window it exists to close.
     */
    private void settleDispatch(UUID jobId, String kind, int itemCount, String itemNoun,
            Supplier<String> dispatch, Runnable compensate) {
        try {
            String runId = dispatch.get();
            if (runId != null) {
                jobs.attachRunId(jobId, runId);
            }
            consecutiveStopSignals.set(0);
            log.info("Dispatched {} job {} ({} {})", kind, jobId, itemCount, itemNoun);
        } catch (DispatchRejectedException e) {
            dsl.transaction(configuration -> {
                compensate.run();
                jobs.delete(jobId);
            });
            log.info("Vistierie declined {} run (status {}); compensated job {}", kind, e.status(), jobId);
            warnIfPersistentStopSignal(e.status());
        } catch (Exception e) {
            log.warn("{} dispatch failed for job {}: {} - leaving awaiting for reconcile",
                    kind, jobId, e.toString());
        }
    }

    private void warnIfPersistentStopSignal(int status) {
        int count = consecutiveStopSignals.incrementAndGet();
        if (count >= STOP_SIGNAL_WARN_THRESHOLD) {
            log.warn("Contradiction sweep has received {} consecutive stop signals (status {}); "
                    + "the sweep is making no progress - check Vistierie: 403 = quota exhausted, "
                    + "409 = agent paused, 404 = wrong base URL", count, status);
        }
    }

    private record StageAJob(UUID jobId, List<String> predicates) {}

    private record StageBJob(UUID jobId) {}
}
