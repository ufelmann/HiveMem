package com.hivemem.contradiction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PredicateCardinalityRepositoryIT extends ContradictionITSupport {

    @Autowired DSLContext dsl;
    @Autowired PredicateCardinalityRepository predicates;

    @Test
    void findUnjudgedReturnsOnlyPredicatesWithAMultiObjectGroup() {
        // key_term: alice has two distinct key_terms -> multi-valued candidate
        insertFact("alice", "key_term", "hiking");
        insertFact("alice", "key_term", "cycling");
        // lives_in: every subject has exactly one object -> not a candidate
        insertFact("alice", "lives_in", "berlin");
        insertFact("bob", "lives_in", "hamburg");

        assertThat(predicates.findUnjudged(10)).containsExactly("key_term");
    }

    /**
     * Guards the {@code lower(btrim(...))} normalization: without it, ' Hiking ' and 'hiking'
     * would look like two distinct objects and wrongly flag this predicate as multi-valued.
     */
    @Test
    void findUnjudgedNormalizesCaseAndWhitespaceBeforeCountingDistinctObjects() {
        insertFact("alice", "key_term_norm", "hiking");
        insertFact("alice", "key_term_norm", " Hiking ");

        assertThat(predicates.findUnjudged(10)).doesNotContain("key_term_norm");
    }

    /**
     * Guards querying {@code active_facts} rather than {@code facts} directly: a rejected or
     * expired fact must not count toward a predicate's distinct-object total, or a retracted value
     * would keep flagging a predicate as multi-valued forever.
     */
    @Test
    void findUnjudgedIgnoresRejectedAndExpiredFacts() {
        insertFact("alice", "key_term_rejected", "hiking");
        dsl.execute("""
                INSERT INTO facts (subject, predicate, "object", status)
                VALUES ('alice', 'key_term_rejected', 'cycling', 'rejected')
                """);

        insertFact("alice", "key_term_expired", "hiking");
        dsl.execute("""
                INSERT INTO facts (subject, predicate, "object", status, valid_until)
                VALUES ('alice', 'key_term_expired', 'cycling', 'committed', now() - interval '1 minute')
                """);

        assertThat(predicates.findUnjudged(10))
                .doesNotContain("key_term_rejected", "key_term_expired");
    }

    @Test
    void findUnjudgedSkipsAPredicateAlreadyHavingARowInEveryStatus() {
        for (String status : List.of("in_flight", "retryable", "decided", "deferred")) {
            String predicate = "key_term_" + status;
            insertFact("alice", predicate, "hiking");
            insertFact("alice", predicate, "cycling");
            dsl.execute("INSERT INTO predicate_cardinality (predicate, status) VALUES (?, ?)",
                    predicate, status);
        }

        assertThat(predicates.findUnjudged(10)).isEmpty();
    }

    @Test
    void reserveWritesInFlightWithAttemptsOneAndNoCardinality() {
        UUID jobId = createJob();

        predicates.reserve(List.of("key_term"), jobId);

        Record row = fetch("key_term");
        assertThat(row.get("status", String.class)).isEqualTo("in_flight");
        assertThat(row.get("attempts", Integer.class)).isEqualTo(1);
        assertThat(row.get("cardinality", String.class)).isNull();
        assertThat(row.get("job_id", UUID.class)).isEqualTo(jobId);
    }

    @Test
    void attemptCounterSurvivesAFullRetryCycleUntilDeferred() {
        UUID job1 = createJob();
        predicates.reserve(List.of("key_term"), job1);
        predicates.applyAttemptRule(job1, 3);
        assertThat(fetch("key_term").get("status", String.class)).isEqualTo("retryable");
        assertThat(fetch("key_term").get("attempts", Integer.class)).isEqualTo(1);

        UUID job2 = createJob();
        predicates.reReserve(List.of("key_term"), job2);
        assertThat(fetch("key_term").get("attempts", Integer.class)).isEqualTo(2);
        predicates.applyAttemptRule(job2, 3);
        assertThat(fetch("key_term").get("status", String.class)).isEqualTo("retryable");
        assertThat(fetch("key_term").get("attempts", Integer.class)).isEqualTo(2);

        UUID job3 = createJob();
        predicates.reReserve(List.of("key_term"), job3);
        assertThat(fetch("key_term").get("attempts", Integer.class)).isEqualTo(3);
        predicates.applyAttemptRule(job3, 3);
        assertThat(fetch("key_term").get("status", String.class)).isEqualTo("deferred");
        assertThat(fetch("key_term").get("attempts", Integer.class)).isEqualTo(3);
    }

    /**
     * The success path, not just the blocked one: without this, simplifying the null-safe
     * {@code IS DISTINCT FROM 'human'} to {@code <> 'human'} would silently stop every judge
     * verdict from applying (NULL <> 'human' is NULL) while the suite stayed green.
     */
    @Test
    void recordVerdictAppliesTheJudgeVerdictOnAReservedRow() {
        UUID jobId = createJob();
        predicates.reserve(List.of("key_term"), jobId);

        boolean applied = predicates.recordVerdict("key_term", "multi_valued", 0.87, "many per cell");

        assertThat(applied).isTrue();
        Record row = fetch("key_term");
        assertThat(row.get("status", String.class)).isEqualTo("decided");
        assertThat(row.get("decided_by", String.class)).isEqualTo("judge");
        assertThat(row.get("cardinality", String.class)).isEqualTo("multi_valued");
        assertThat(row.get("confidence", Float.class)).isEqualTo(0.87f);
        assertThat(row.get("rationale", String.class)).isEqualTo("many per cell");
    }

    @Test
    void setByHumanSurvivesALaterRecordVerdict() {
        predicates.setByHuman("key_term", "multi_valued", "obviously repeated per cell");

        predicates.recordVerdict("key_term", "single_valued", 0.9, "judge disagreed");

        Record row = fetch("key_term");
        assertThat(row.get("decided_by", String.class)).isEqualTo("human");
        assertThat(row.get("cardinality", String.class)).isEqualTo("multi_valued");
        assertThat(row.get("rationale", String.class)).isEqualTo("obviously repeated per cell");
    }

    /**
     * A judge verdict carries a confidence score and a job reference; a human override that
     * supersedes it must not let either ride along, or a human decision would misleadingly display
     * a judge's confidence number.
     */
    @Test
    void setByHumanClearsStaleJudgeConfidenceAndJobId() {
        UUID jobId = createJob();
        predicates.reserve(List.of("key_term"), jobId);
        predicates.recordVerdict("key_term", "multi_valued", 0.9, "judge's first guess");

        predicates.setByHuman("key_term", "single_valued", "human knows better");

        Record row = fetch("key_term");
        assertThat(row.get("confidence", Float.class)).isNull();
        assertThat(row.get("job_id", UUID.class)).isNull();
    }

    @Test
    void singleValuedPredicatesReturnsOnlyDecidedSingleValued() {
        predicates.setByHuman("lives_in", "single_valued", "one home at a time");
        dsl.execute("INSERT INTO predicate_cardinality (predicate, cardinality, status, decided_by, decided_at) "
                + "VALUES ('key_term', 'multi_valued', 'decided', 'judge', now())");
        dsl.execute("INSERT INTO predicate_cardinality (predicate, status) VALUES ('x', 'in_flight')");
        dsl.execute("INSERT INTO predicate_cardinality (predicate, status) VALUES ('y', 'deferred')");
        // Realistic case the status filter alone guards: a predicate was decided single_valued,
        // then knocked back to non-decided by a re-judging job. Its cardinality column still reads
        // 'single_valued' - only the status check keeps it out of the gate until re-decided.
        dsl.execute("INSERT INTO predicate_cardinality (predicate, cardinality, status) "
                + "VALUES ('was_decided_then_requeued', 'single_valued', 'retryable')");
        dsl.execute("INSERT INTO predicate_cardinality (predicate, cardinality, status) "
                + "VALUES ('still_in_flight_single_valued', 'single_valued', 'in_flight')");

        assertThat(predicates.singleValuedPredicates()).containsExactly("lives_in");
    }

    @Test
    void compensateUndoesExactlyThisTicksReservations() {
        UUID job1 = createJob();
        predicates.reserve(List.of("fresh_predicate"), job1);
        predicates.reserve(List.of("stale_predicate"), job1);
        predicates.applyAttemptRule(job1, 5);
        assertThat(fetch("stale_predicate").get("status", String.class)).isEqualTo("retryable");

        UUID job2 = createJob();
        predicates.reserve(List.of("fresh_predicate2"), job2);
        predicates.reReserve(List.of("stale_predicate"), job2);
        assertThat(fetch("stale_predicate").get("attempts", Integer.class)).isEqualTo(2);

        predicates.compensate(job2);

        assertThat(fetch("fresh_predicate2")).isNull();
        Record reverted = fetch("stale_predicate");
        assertThat(reverted.get("status", String.class)).isEqualTo("retryable");
        assertThat(reverted.get("attempts", Integer.class)).isEqualTo(1);
        // job1's own fresh reservation must be untouched by job2's compensation: compensate is
        // scoped to job_id, not just to matching (status, attempts) shapes.
        Record untouched = fetch("fresh_predicate");
        assertThat(untouched.get("status", String.class)).isEqualTo("retryable");
        assertThat(untouched.get("job_id", UUID.class)).isEqualTo(job1);
    }

    private UUID createJob() {
        return dsl.fetchOne("""
                INSERT INTO contradiction_jobs (correlation_id, kind, item_count)
                VALUES (?, 'cardinality', 1)
                RETURNING id
                """, UUID.randomUUID()).get("id", UUID.class);
    }

    private void insertFact(String subject, String predicate, String object) {
        dsl.execute("""
                INSERT INTO facts (subject, predicate, "object", status)
                VALUES (?, ?, ?, 'committed')
                """, subject, predicate, object);
    }

    private Record fetch(String predicate) {
        return dsl.fetchOne("SELECT * FROM predicate_cardinality WHERE predicate = ?", predicate);
    }
}
