package com.hivemem.contradiction;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Pins {@link ContradictionReconcileSweep}'s contract: a stale dispatched job (of either kind) is
 * claimed, its rows follow the attempt rule, and the job itself is marked failed - the mechanism
 * that guarantees every dispatched item eventually reaches a terminal status even if the webhook
 * that was supposed to close it out never arrives. Also pins {@code autoCloseInactive} running
 * once per tick, and the crash-recovery path for a job stuck {@code processing}.
 */
class ContradictionReconcileSweepIT extends ContradictionITSupport {

    @Autowired DSLContext dsl;
    @Autowired ContradictionReconcileSweep sweep;
    @Autowired ContradictionJobRepository jobs;
    @Autowired ContradictionRepository pairs;
    @Autowired PredicateCardinalityRepository cardinality;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("hivemem.contradiction.enabled", () -> "true");
        r.add("hivemem.queen.enabled", () -> "true");
        r.add("hivemem.queen.contradiction-webhook-token", () -> "test-contradiction-webhook-token");
        r.add("hivemem.contradiction.max-attempts", () -> "3");
        r.add("hivemem.contradiction.stale-threshold", () -> "10m");
    }

    // ---- Stale pairs job ------------------------------------------------------------------

    @Test
    void staleAwaitingPairsJobIsClaimedFailedAndRowsBecomeRetryable() {
        UUID a = insertFact("s1", "p1", "A");
        UUID b = insertFact("s1", "p1", "B");
        UUID jobId = createJob("pairs");
        insertPairRow(a, b, "s1", "p1", jobId, 1);
        age(jobId);
        // An unrelated, non-stale cardinality row under a different job: proves the pairs job's
        // reconcile is scoped to its own job_id rather than sweeping predicate_cardinality wholesale.
        UUID otherCardinalityJob = createJob("cardinality");
        insertCardinalityRow("unrelated_predicate", otherCardinalityJob, 1);

        sweep.reconcile();

        assertThat(jobStatus(jobId)).isEqualTo("failed");
        Record row = fetchPair("s1");
        assertThat(row.get("status", String.class)).isEqualTo("retryable");
        assertThat(row.get("attempts", Integer.class)).isEqualTo(1);
        Record untouched = fetchCardinality("unrelated_predicate");
        assertThat(untouched.get("status", String.class)).isEqualTo("in_flight");
        assertThat(untouched.get("attempts", Integer.class)).isEqualTo(1);
    }

    // ---- Stale cardinality job --------------------------------------------------------------

    @Test
    void staleAwaitingCardinalityJobIsClaimedFailedAndRowsBecomeRetryable() {
        UUID jobId = createJob("cardinality");
        insertCardinalityRow("key_term", jobId, 1);
        age(jobId);
        // An unrelated, non-stale pairs row under a different job: an empty fact_contradictions
        // table can never distinguish "the cardinality job correctly used
        // PredicateCardinalityRepository" from "the sweep dispatched to both repositories" (both
        // leave an empty table empty). Seeding a real, untouched row and asserting it stays
        // untouched is the only way to actually pin the per-kind dispatch.
        UUID a = insertFact("s0", "p0", "A");
        UUID b = insertFact("s0", "p0", "B");
        UUID otherPairsJob = createJob("pairs");
        insertPairRow(a, b, "s0", "p0", otherPairsJob, 1);

        sweep.reconcile();

        assertThat(jobStatus(jobId)).isEqualTo("failed");
        Record row = fetchCardinality("key_term");
        assertThat(row.get("status", String.class)).isEqualTo("retryable");
        assertThat(row.get("attempts", Integer.class)).isEqualTo(1);
        Record untouched = fetchPair("s0");
        assertThat(untouched.get("status", String.class)).isEqualTo("in_flight");
        assertThat(untouched.get("attempts", Integer.class)).isEqualTo(1);
    }

    // ---- Attempt ceiling --------------------------------------------------------------------

    @Test
    void staleJobAtAttemptCeilingParksRowsDeferred() {
        UUID a = insertFact("s2", "p2", "A");
        UUID b = insertFact("s2", "p2", "B");
        UUID jobId = createJob("pairs");
        insertPairRow(a, b, "s2", "p2", jobId, 3); // max-attempts overridden to 3
        age(jobId);

        sweep.reconcile();

        Record row = fetchPair("s2");
        assertThat(row.get("status", String.class)).isEqualTo("deferred");
        assertThat(row.get("attempts", Integer.class)).isEqualTo(3);
    }

    // ---- Fresh job untouched ------------------------------------------------------------------

    @Test
    void freshJobIsUntouched() {
        UUID a = insertFact("s3", "p3", "A");
        UUID b = insertFact("s3", "p3", "B");
        UUID jobId = createJob("pairs");
        insertPairRow(a, b, "s3", "p3", jobId, 1);
        // deliberately not aged - updated_at stays "now"

        sweep.reconcile();

        assertThat(jobStatus(jobId)).isEqualTo("awaiting");
        assertThat(fetchPair("s3").get("status", String.class)).isEqualTo("in_flight");
    }

    // ---- Terminal job never returned ------------------------------------------------------------

    @Test
    void terminalJobsAreNeverTouchedEvenWhenAged() {
        UUID doneJob = createJob("pairs");
        assertThat(jobs.claim(doneJob)).isTrue();
        assertThat(jobs.markDone(doneJob)).isTrue();
        age(doneJob);

        UUID failedJob = createJob("cardinality");
        assertThat(jobs.markFailed(failedJob)).isTrue();
        age(failedJob);

        sweep.reconcile();

        assertThat(jobStatus(doneJob)).isEqualTo("done");
        assertThat(jobStatus(failedJob)).isEqualTo("failed");
    }

    // ---- Claim race: a live claim beats the sweep to an awaiting job ------------------------------

    /**
     * Mirrors {@code SeparationClaimRecoveryIT#degradeSkipsWhenJobAlreadyClaimed}: hands {@link
     * ContradictionReconcileSweep#recover} a {@link ContradictionJobRepository.Job} snapshot taken
     * <em>before</em> a simulated webhook wins the claim, so the guard is exercised deterministically
     * rather than by hoping two real threads interleave a particular way. If {@code recover} skipped
     * its own {@code reclaimStale} gate and worked from the stale snapshot regardless, it would
     * still flip the in-flight row to {@code retryable} out from under the "live" owner - so this
     * pins that it makes no write at all once the row is no longer its to take.
     */
    @Test
    void recoverSkipsAJobAlreadyClaimedByAnotherOwner() {
        UUID a = insertFact("s4", "p4", "A");
        UUID b = insertFact("s4", "p4", "B");
        UUID jobId = createJob("pairs");
        insertPairRow(a, b, "s4", "p4", jobId, 1);
        age(jobId);

        // The sweep's own findStale would return this snapshot (status 'awaiting') right now...
        ContradictionJobRepository.Job staleSnapshot =
                jobs.findStale(Duration.ofMinutes(10), 10).stream()
                        .filter(j -> j.id().equals(jobId)).findFirst().orElseThrow();
        // ...but a webhook (simulated directly, since the webhook endpoint is a later task) claims
        // the job first, in between the sweep's read and its action.
        assertThat(jobs.claim(jobId)).isTrue();

        sweep.recover(staleSnapshot);

        assertThat(jobStatus(jobId)).isEqualTo("processing");
        assertThat(fetchPair("s4").get("status", String.class)).isEqualTo("in_flight");
    }

    /**
     * The realistic end-to-end shape of the same race, run with real concurrency rather than a
     * hand-fed snapshot: whichever of the two threads wins, the attempt rule must run at most once
     * and the row must end up in exactly one of the two legitimate end states, never a mix of both
     * (e.g. {@code retryable} while the job is still {@code processing} with no terminal write).
     */
    @Test
    void concurrentClaimAndReconcileNeverDoubleApplyTheAttemptRule() throws Exception {
        UUID a = insertFact("s4b", "p4b", "A");
        UUID b = insertFact("s4b", "p4b", "B");
        UUID jobId = createJob("pairs");
        insertPairRow(a, b, "s4b", "p4b", jobId, 1);
        age(jobId);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Runnable webhookClaim = () -> {
            ready.countDown();
            await(go);
            jobs.claim(jobId);
        };
        Runnable reconcileTick = () -> {
            ready.countDown();
            await(go);
            sweep.reconcile();
        };
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            var f1 = pool.submit(webhookClaim);
            var f2 = pool.submit(reconcileTick);
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                // Release both tasks even if the readiness assertion above fails - otherwise a
                // failed wait leaves both tasks parked on await(go) forever, and the
                // try-with-resources close() below blocks indefinitely instead of the test failing
                // with the actual assertion error.
                go.countDown();
            }
            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);
        }

        String status = jobStatus(jobId);
        Record row = fetchPair("s4b");
        if ("failed".equals(status)) {
            // The sweep won the race: it must have applied the attempt rule exactly once.
            assertThat(row.get("status", String.class)).isEqualTo("retryable");
            assertThat(row.get("attempts", Integer.class)).isEqualTo(1);
        } else {
            // The webhook won: the sweep must have made no write at all.
            assertThat(status).isEqualTo("processing");
            assertThat(row.get("status", String.class)).isEqualTo("in_flight");
        }
    }

    // ---- autoCloseInactive --------------------------------------------------------------------

    @Test
    void autoCloseInactiveSupersedesAPendingRowWhoseFactWentInactiveButLeavesOthersAlone() {
        UUID pendingA = insertFact("s5", "p5", "A");
        UUID pendingB = insertFact("s5", "p5", "B");
        recordContradiction(pendingA, pendingB, "s5", "p5", "pending");
        invalidate(pendingA);

        UUID deferredA = insertFact("s6", "p6", "A");
        UUID deferredB = insertFact("s6", "p6", "B");
        recordContradiction(deferredA, deferredB, "s6", "p6", "deferred");
        invalidate(deferredA);

        UUID inFlightA = insertFact("s7", "p7", "A");
        UUID inFlightB = insertFact("s7", "p7", "B");
        recordContradiction(inFlightA, inFlightB, "s7", "p7", "in_flight");
        invalidate(inFlightA);

        sweep.reconcile();

        assertThat(fetchPair("s5").get("status", String.class)).isEqualTo("superseded");
        assertThat(fetchPair("s6").get("status", String.class)).isEqualTo("deferred");
        assertThat(fetchPair("s7").get("status", String.class)).isEqualTo("in_flight");
    }

    /**
     * NOT a proof that {@code autoCloseInactive} runs exactly once per tick rather than once per
     * job — {@code autoCloseInactive} is idempotent, so that distinction is invisible in the DB
     * state a single {@code reconcile()} call leaves behind; only counting the actual invocation
     * (done at the unit level in {@code ContradictionReconcileSweepTest}) can tell "once, after the
     * loop" apart from "once per stale job, inside the loop". What this test does pin: multiple
     * stale jobs of different kinds and an unrelated inactive-fact supersession all resolve
     * correctly within the same tick, with neither interfering with the other.
     */
    @Test
    void multipleStaleJobsOfDifferentKindsAndAnUnrelatedSupersessionAllResolveInOneTick() {
        UUID a1 = insertFact("s8", "p8", "A");
        UUID b1 = insertFact("s8", "p8", "B");
        UUID job1 = createJob("pairs");
        insertPairRow(a1, b1, "s8", "p8", job1, 1);
        age(job1);

        UUID job2 = createJob("cardinality");
        insertCardinalityRow("s9-predicate", job2, 1);
        age(job2);

        UUID pendingA = insertFact("s10", "p10", "A");
        UUID pendingB = insertFact("s10", "p10", "B");
        recordContradiction(pendingA, pendingB, "s10", "p10", "pending");
        invalidate(pendingA);

        sweep.reconcile();

        assertThat(jobStatus(job1)).isEqualTo("failed");
        assertThat(jobStatus(job2)).isEqualTo("failed");
        assertThat(fetchPair("s10").get("status", String.class)).isEqualTo("superseded");
    }

    // ---- Stuck-processing crash recovery --------------------------------------------------------

    /**
     * A job this sweep (or a future webhook) claimed on an earlier tick, then crashed before
     * reaching a terminal write: {@code claim()} alone can never recover it (it only matches
     * {@code status='awaiting'}), so without {@link ContradictionJobRepository#reclaimStale} this
     * job would sit {@code processing} forever, invisible to every later sweep despite {@link
     * ContradictionJobRepository#findStale} dutifully re-offering it on every tick.
     */
    @Test
    void stuckProcessingJobIsRecoveredNotStrandedForever() {
        UUID a = insertFact("s11", "p11", "A");
        UUID b = insertFact("s11", "p11", "B");
        UUID jobId = createJob("pairs");
        assertThat(jobs.claim(jobId)).isTrue(); // simulate a crash right after claim()
        insertPairRow(a, b, "s11", "p11", jobId, 1);
        age(jobId);

        sweep.reconcile();

        assertThat(jobStatus(jobId)).isEqualTo("failed");
        assertThat(fetchPair("s11").get("status", String.class)).isEqualTo("retryable");
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private UUID createJob(String kind) {
        return jobs.create(UUID.randomUUID(), kind, 1);
    }

    private void insertPairRow(UUID factA, UUID factB, String subject, String predicate, UUID jobId, int attempts) {
        dsl.execute("""
                INSERT INTO fact_contradictions (fact_a, fact_b, subject, predicate, status, job_id, attempts)
                VALUES (?, ?, ?, ?, 'in_flight', ?, ?)
                """, factA, factB, subject, predicate, jobId, attempts);
    }

    private void insertCardinalityRow(String predicate, UUID jobId, int attempts) {
        dsl.execute("""
                INSERT INTO predicate_cardinality (predicate, status, job_id, attempts)
                VALUES (?, 'in_flight', ?, ?)
                """, predicate, jobId, attempts);
    }

    private void invalidate(UUID factId) {
        dsl.execute("UPDATE facts SET valid_until = now() - interval '1 minute' WHERE id = ?", factId);
    }

    private void age(UUID jobId) {
        dsl.execute("UPDATE contradiction_jobs SET updated_at = now() - interval '1 hour' WHERE id = ?", jobId);
    }

    private String jobStatus(UUID jobId) {
        return dsl.fetchOne("SELECT status FROM contradiction_jobs WHERE id = ?", jobId)
                .get("status", String.class);
    }

    private Record fetchPair(String subject) {
        return dsl.fetchOne("SELECT * FROM fact_contradictions WHERE subject = ?", subject);
    }

    private Record fetchCardinality(String predicate) {
        return dsl.fetchOne("SELECT * FROM predicate_cardinality WHERE predicate = ?", predicate);
    }
}
