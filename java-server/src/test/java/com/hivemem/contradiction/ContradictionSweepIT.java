package com.hivemem.contradiction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Pins {@link ContradictionSweep}'s contract: one dispatched run per tick, Stage A takes
 * precedence over Stage B, the daily ceiling is advisory-locked against concurrent ticks, and a
 * declined dispatch leaves no trace while a transport failure leaves the job for reconcile.
 *
 * <p>{@code batch-size}/{@code cardinality-batch-size} are overridden small so the capping tests
 * stay fast; {@code max-runs-per-day} keeps the default (4) since several tests pin exactly that
 * number.
 */
@Import(ContradictionSweepIT.SweepTestConfig.class)
class ContradictionSweepIT extends ContradictionITSupport {

    @Autowired DSLContext dsl;
    @Autowired ContradictionSweep sweep;
    @Autowired ContradictionJobRepository jobs;
    @Autowired PredicateCardinalityRepository cardinality;
    @Autowired VistierieCardinalityClient cardinalityClient;
    @Autowired VistierieContradictionClient pairsClient;

    @DynamicPropertySource
    static void sweepProps(DynamicPropertyRegistry r) {
        r.add("hivemem.contradiction.enabled", () -> "true");
        r.add("hivemem.queen.enabled", () -> "true");
        r.add("hivemem.queen.contradiction-webhook-token", () -> "test-contradiction-webhook-token");
        r.add("hivemem.contradiction.batch-size", () -> "3");
        r.add("hivemem.contradiction.cardinality-batch-size", () -> "2");
        r.add("hivemem.contradiction.max-runs-per-day", () -> "4");
    }

    @BeforeEach
    void resetMocks() {
        reset(cardinalityClient, pairsClient);
    }

    // ---- Stage selection ----------------------------------------------------------------

    @Test
    void unjudgedPredicateDispatchesStageAOnly() {
        insertFact("alice", "key_term", "Berlin");
        insertFact("alice", "key_term", "Munich");
        // A second predicate that is ALSO Stage-B-eligible (already decided single-valued, with a
        // real conflicting pair) so this test cannot pass merely because Stage B happens to have
        // nothing to do - Stage A must win on precedence, not by default.
        cardinality.setByHuman("lives_in", "single_valued", "seed");
        insertFact("bob", "lives_in", "Hamburg");
        insertFact("bob", "lives_in", "Cologne");

        sweep.tick();

        verify(cardinalityClient, times(1)).dispatch(any(), any());
        verify(pairsClient, never()).dispatch(any(), any());
        assertThat(jobKind()).containsExactly("cardinality");
    }

    @Test
    void allPredicatesJudgedDispatchesStageB() {
        cardinality.setByHuman("lives_in", "single_valued", "seed");
        insertFact("alice", "lives_in", "Berlin");
        insertFact("alice", "lives_in", "Munich");

        sweep.tick();

        verify(pairsClient, times(1)).dispatch(any(), any());
        verify(cardinalityClient, never()).dispatch(any(), any());
        assertThat(jobKind()).containsExactly("pairs");
    }

    // ---- Commit-before-dispatch ordering --------------------------------------------------

    /**
     * Queries through a JDBC connection opened directly against the Testcontainers Postgres
     * (bypassing the application's {@code DataSource}/jOOQ entirely) rather than reusing {@link
     * #dsl}: {@code dsl}'s connection is transaction-aware and would happily see the sweep's own
     * uncommitted writes from the very same session even if {@code dispatch()} were mistakenly
     * called from inside the write transaction, making that mutation undetectable. A genuinely
     * independent connection only sees the row if the transaction actually committed first — under
     * Postgres's default READ COMMITTED isolation, an uncommitted row is invisible to every other
     * session.
     *
     * <p>The final assertion on {@code vistierie_run_id} matters beyond the in-{@code Answer}
     * checks above: those live inside a Mockito {@code Answer}, and any {@code SQLException} thrown
     * there (a bad JDBC URL, the container's port changing, a refused connection) is a checked
     * exception that {@code settleDispatch}'s {@code catch (Exception e)} would swallow as an
     * ordinary transport failure — the test's mock-invocation-count assertion would still pass,
     * silently turning an infrastructure hiccup into a false green. Asserting the run id proves the
     * {@code Answer} actually ran to normal completion and returned "run-visible" rather than being
     * caught and discarded.
     */
    @Test
    void jobAndReservationsAreCommittedBeforeDispatchReturns() throws Exception {
        cardinality.setByHuman("lives_in", "single_valued", "seed");
        insertFact("alice", "lives_in", "Berlin");
        insertFact("alice", "lives_in", "Munich");

        when(pairsClient.dispatch(any(), any())).thenAnswer(invocation -> {
            UUID correlationId = invocation.getArgument(0);
            try (java.sql.Connection independent = java.sql.DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
                try (var ps = independent.prepareStatement(
                        "SELECT status FROM contradiction_jobs WHERE correlation_id = ?")) {
                    ps.setObject(1, correlationId);
                    try (var rs = ps.executeQuery()) {
                        assertThat(rs.next())
                                .as("job row must already be committed and visible from an independent "
                                        + "connection before dispatch() is called")
                                .isTrue();
                        assertThat(rs.getString("status")).isEqualTo("awaiting");
                    }
                }
                try (var ps = independent.prepareStatement(
                        "SELECT count(*) AS c FROM fact_contradictions WHERE status = 'in_flight'");
                        var rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt("c"))
                            .as("reservation must already be committed and visible before dispatch()")
                            .isEqualTo(1);
                }
            }
            return "run-visible";
        });

        sweep.tick();

        verify(pairsClient, times(1)).dispatch(any(), any());
        Record job = dsl.fetchOne("SELECT vistierie_run_id FROM contradiction_jobs");
        assertThat(job.get("vistierie_run_id", String.class))
                .as("proves the Answer ran to completion rather than being swallowed as a transport failure")
                .isEqualTo("run-visible");
    }

    // ---- Failure handling ------------------------------------------------------------------

    @Test
    void dispatchRejectedLeavesNoNetJobOrReservationAndDecrementsAReReservedRow() {
        cardinality.setByHuman("lives_in", "single_valued", "seed");
        // A pre-existing retryable row from an earlier (unrelated) job, attempts = 1.
        UUID a = insertFact("s1", "lives_in", "A");
        UUID b = insertFact("s1", "lives_in", "B");
        recordContradiction(a, b, "s1", "lives_in", "retryable");

        when(pairsClient.dispatch(any(), any())).thenThrow(new DispatchRejectedException(403, "quota"));

        sweep.tick();

        assertThat(countJobs()).isEqualTo(0);
        assertThat(jobs.countToday()).isEqualTo(0);
        Record row = dsl.fetchOne("SELECT status, attempts FROM fact_contradictions WHERE subject = 's1'");
        assertThat(row.get("status", String.class)).isEqualTo("retryable");
        assertThat(row.get("attempts", Integer.class)).isEqualTo(1);
    }

    @Test
    void transportFailureLeavesJobAwaitingWithReservationsIntact() {
        cardinality.setByHuman("lives_in", "single_valued", "seed");
        insertFact("s2", "lives_in", "X");
        insertFact("s2", "lives_in", "Y");

        when(pairsClient.dispatch(any(), any())).thenThrow(new RuntimeException("connect timed out"));

        sweep.tick();

        Record job = dsl.fetchOne("SELECT status, vistierie_run_id, kind FROM contradiction_jobs");
        assertThat(job.get("status", String.class)).isEqualTo("awaiting");
        assertThat(job.get("vistierie_run_id", String.class)).isNull();
        assertThat(job.get("kind", String.class)).isEqualTo("pairs");
        Record pair = dsl.fetchOne("SELECT status FROM fact_contradictions WHERE subject = 's2'");
        assertThat(pair.get("status", String.class)).isEqualTo("in_flight");
        assertThat(jobs.countToday()).isEqualTo(1);
    }

    // ---- Daily ceiling under concurrency -----------------------------------------------------

    @Test
    void concurrentTicksAtCeilingMinusOneYieldExactlyOneMoreJob() throws Exception {
        when(cardinalityClient.dispatch(any(), any())).thenReturn("run-x");
        when(pairsClient.dispatch(any(), any())).thenReturn("run-y");

        int repeats = 8;
        for (int i = 0; i < repeats; i++) {
            dsl.execute("DELETE FROM predicate_cardinality");
            dsl.execute("DELETE FROM fact_contradictions");
            dsl.execute("DELETE FROM contradiction_jobs");
            dsl.execute("DELETE FROM facts");
            seedThreeJobsToday();
            // Two distinct unjudged predicates so both threads have real work available and are not
            // merely both discovering "nothing to do" - the ceiling, not the work, must be what
            // blocks the loser.
            insertFact("s1", "p1", "A");
            insertFact("s1", "p1", "B");
            insertFact("s2", "p2", "C");
            insertFact("s2", "p2", "D");
            assertThat(jobs.countToday()).isEqualTo(3);

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Runnable task = () -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                sweep.tick();
            };
            // Java 25: ExecutorService is AutoCloseable, and close() awaits termination - a
            // try-with-resources here means a failure mid-loop (a thrown assertion, a timeout) still
            // shuts the pool down instead of leaking two non-daemon threads for the rest of the JVM.
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                var f1 = pool.submit(task);
                var f2 = pool.submit(task);
                assertThat(ready.await(5, TimeUnit.SECONDS))
                        .as("both threads must reach the starting line before either is released")
                        .isTrue();
                go.countDown(); // release both threads at (as close as the JVM allows to) the same instant
                f1.get(10, TimeUnit.SECONDS);
                f2.get(10, TimeUnit.SECONDS);
            }

            assertThat(jobs.countToday())
                    .as("iteration %d: the ceiling must let exactly one of the two concurrent ticks through", i)
                    .isEqualTo(4);
        }
    }

    // ---- Empty pool --------------------------------------------------------------------------

    @Test
    void emptyPoolDispatchesNothingAndConsumesNoSlot() {
        sweep.tick();

        verify(cardinalityClient, never()).dispatch(any(), any());
        verify(pairsClient, never()).dispatch(any(), any());
        assertThat(jobs.countToday()).isEqualTo(0);
        assertThat(countJobs()).isEqualTo(0);
    }

    // ---- No double-dispatch of an already-reserved pair --------------------------------------

    @Test
    void reservedPairIsNotSelectedAgainByTheNextTick() {
        cardinality.setByHuman("lives_in", "single_valued", "seed");
        insertFact("alice", "lives_in", "Berlin");
        insertFact("alice", "lives_in", "Munich");
        when(pairsClient.dispatch(any(), any())).thenReturn("run-1");

        sweep.tick();
        sweep.tick();

        verify(pairsClient, times(1)).dispatch(any(), any());
        assertThat(countJobs()).isEqualTo(1);
    }

    // ---- Stage-A payload shape -----------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void stageAPayloadCarriesPinnedSamplesAndNoCounts() {
        // "alice" has 4 distinct key_term objects (the largest group); "bob" has 2, and must be
        // ignored in favor of alice's group.
        insertFact("alice", "key_term", "Zurich");
        insertFact("alice", "key_term", "Berlin");
        insertFact("alice", "key_term", "Munich");
        insertFact("alice", "key_term", "Hamburg");
        insertFact("bob", "key_term", "Paris");
        insertFact("bob", "key_term", "Lyon");
        when(cardinalityClient.dispatch(any(), any())).thenReturn("run-a");

        sweep.tick();

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(cardinalityClient).dispatch(any(), captor.capture());
        List<PredicatePayload> sent = captor.getValue();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).predicate()).isEqualTo("key_term");
        // Lexicographically first 3 of alice's 4 distinct objects (the larger group).
        assertThat(sent.get(0).sample_objects()).containsExactly("Berlin", "Hamburg", "Munich");
    }

    // ---- Re-reservation capping ----------------------------------------------------------------

    @Test
    void reReservationIsCappedAtBatchSize() {
        cardinality.setByHuman("lives_in", "single_valued", "seed");
        for (int i = 0; i < 5; i++) {
            UUID a = insertFact("subj" + i, "lives_in", "A");
            UUID b = insertFact("subj" + i, "lives_in", "B");
            recordContradiction(a, b, "subj" + i, "lives_in", "retryable");
        }
        when(pairsClient.dispatch(any(), any())).thenReturn("run-cap");

        sweep.tick();

        int inFlight = dsl.fetchOne("SELECT count(*) AS c FROM fact_contradictions WHERE status = 'in_flight'")
                .get("c", Integer.class);
        int retryable = dsl.fetchOne("SELECT count(*) AS c FROM fact_contradictions WHERE status = 'retryable'")
                .get("c", Integer.class);
        // batch-size is overridden to 3 for this test class.
        assertThat(inFlight).isEqualTo(3);
        assertThat(retryable).isEqualTo(2);
        Record job = dsl.fetchOne("SELECT item_count FROM contradiction_jobs");
        assertThat(job.get("item_count", Integer.class)).isEqualTo(3);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private void seedThreeJobsToday() {
        for (int i = 0; i < 3; i++) {
            dsl.execute("""
                    INSERT INTO contradiction_jobs (correlation_id, kind, item_count, status)
                    VALUES (?, 'pairs', 1, 'done')
                    """, UUID.randomUUID());
        }
    }

    private List<String> jobKind() {
        var rows = dsl.fetch("SELECT kind FROM contradiction_jobs");
        return rows.map(r -> r.get("kind", String.class));
    }

    private int countJobs() {
        return dsl.fetchOne("SELECT count(*) AS c FROM contradiction_jobs").get("c", Integer.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SweepTestConfig {
        @Bean
        @Primary
        VistierieCardinalityClient cardinalityClientMock() {
            return mock(VistierieCardinalityClient.class);
        }

        @Bean
        @Primary
        VistierieContradictionClient pairsClientMock() {
            return mock(VistierieContradictionClient.class);
        }
    }
}
