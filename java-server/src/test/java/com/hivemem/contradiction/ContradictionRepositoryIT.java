package com.hivemem.contradiction;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ContradictionRepositoryIT extends ContradictionITSupport {

    @Autowired DSLContext dsl;
    @Autowired ContradictionRepository pairs;

    @Test
    void reserveWritesInFlightWithAttemptsOneJobIdAndSuggestedKeep() {
        UUID jobId = createJob();
        OffsetDateTime now = OffsetDateTime.now();
        UUID older = insertFact("alice", "lives_in", "Berlin", now.minusDays(2), null, now.minusDays(2), 1.0);
        UUID newer = insertFact("alice", "lives_in", "Munich", now, null, now, 1.0);

        List<UUID> inserted = pairs.reserve(List.of(candidateOf("alice", "lives_in", older, newer)), jobId);

        assertThat(inserted).hasSize(1);
        Record row = fetch(inserted.get(0));
        assertThat(row.get("status", String.class)).isEqualTo("in_flight");
        assertThat(row.get("attempts", Integer.class)).isEqualTo(1);
        assertThat(row.get("job_id", UUID.class)).isEqualTo(jobId);
        // Confirms suggested_keep is persisted from the selector's output; the precedence itself
        // (which side wins and why) is pinned by ContradictionWinnerSelectorTest, not here.
        assertThat(row.get("suggested_keep", UUID.class)).isEqualTo(newer);
    }

    @Test
    void reserveIsIdempotentAgainstAnAlreadyRecordedPairRegardlessOfOrder() {
        UUID jobId = createJob();
        UUID a = insertFact("alice", "lives_in", "Berlin");
        UUID b = insertFact("alice", "lives_in", "Munich");
        recordContradiction(b, a, "alice", "lives_in", "pending"); // recorded in the opposite order
        UUID c = insertFact("bob", "lives_in", "Hamburg");
        UUID d = insertFact("bob", "lives_in", "Cologne");

        List<UUID> inserted = pairs.reserve(
                List.of(candidateOf("alice", "lives_in", a, b), candidateOf("bob", "lives_in", c, d)), jobId);

        assertThat(inserted).hasSize(1);
        assertThat(fetch(inserted.get(0)).get("subject", String.class)).isEqualTo("bob");
        assertThat(countRows()).isEqualTo(2); // the pre-existing alice/bob row plus the new bob row
    }

    /** The load-bearing one: attempts survives a full reserve -> retryable -> reReserve -> deferred cycle. */
    @Test
    void attemptCounterSurvivesAFullRetryCycleUntilDeferred() {
        UUID job1 = createJob();
        UUID a = insertFact("alice", "lives_in", "Berlin");
        UUID b = insertFact("alice", "lives_in", "Munich");
        UUID pairId = pairs.reserve(List.of(candidateOf("alice", "lives_in", a, b)), job1).get(0);

        pairs.applyAttemptRule(job1, 3);
        assertThat(fetch(pairId).get("status", String.class)).isEqualTo("retryable");
        assertThat(fetch(pairId).get("attempts", Integer.class)).isEqualTo(1);

        UUID job2 = createJob();
        assertThat(pairs.reReserve(job2, 10)).containsExactly(pairId);
        assertThat(fetch(pairId).get("attempts", Integer.class)).isEqualTo(2);
        pairs.applyAttemptRule(job2, 3);
        assertThat(fetch(pairId).get("status", String.class)).isEqualTo("retryable");
        assertThat(fetch(pairId).get("attempts", Integer.class)).isEqualTo(2);

        UUID job3 = createJob();
        assertThat(pairs.reReserve(job3, 10)).containsExactly(pairId);
        assertThat(fetch(pairId).get("attempts", Integer.class)).isEqualTo(3);
        pairs.applyAttemptRule(job3, 3);
        assertThat(fetch(pairId).get("status", String.class)).isEqualTo("deferred");
        assertThat(fetch(pairId).get("attempts", Integer.class)).isEqualTo(3);
    }

    @Test
    void reReserveIsCappedAtBatchSize() {
        UUID job = createJob();
        for (int i = 0; i < 50; i++) {
            UUID a = insertFact("subject" + i, "lives_in", "A");
            UUID b = insertFact("subject" + i, "lives_in", "B");
            recordContradiction(a, b, "subject" + i, "lives_in", "retryable");
        }

        List<UUID> reReserved = pairs.reReserve(job, 25);

        assertThat(reReserved).hasSize(25);
        assertThat(countByStatus("in_flight")).isEqualTo(25);
        assertThat(countByStatus("retryable")).isEqualTo(25);
    }

    @Test
    void reReservePicksOldestFirstDeterministically() {
        UUID job = createJob();
        UUID a1 = insertFact("s1", "lives_in", "A");
        UUID b1 = insertFact("s1", "lives_in", "B");
        recordContradiction(a1, b1, "s1", "lives_in", "retryable");
        UUID older = fetchIdForSubject("s1");
        dsl.execute("UPDATE fact_contradictions SET detected_at = now() - interval '2 days' WHERE id = ?", older);

        UUID a2 = insertFact("s2", "lives_in", "A");
        UUID b2 = insertFact("s2", "lives_in", "B");
        recordContradiction(a2, b2, "s2", "lives_in", "retryable");
        UUID newer = fetchIdForSubject("s2");
        dsl.execute("UPDATE fact_contradictions SET detected_at = now() - interval '1 day' WHERE id = ?", newer);

        assertThat(pairs.reReserve(job, 1)).containsExactly(older);
    }

    @Test
    void recordVerdictTrueMarksPendingAndIsNotAppliedTwice() {
        UUID job = createJob();
        UUID a = insertFact("alice", "lives_in", "Berlin");
        UUID b = insertFact("alice", "lives_in", "Munich");
        UUID pairId = pairs.reserve(List.of(candidateOf("alice", "lives_in", a, b)), job).get(0);

        boolean applied = pairs.recordVerdict(pairId, true, 0.9, "different cities");

        assertThat(applied).isTrue();
        Record row = fetch(pairId);
        assertThat(row.get("status", String.class)).isEqualTo("pending");
        assertThat(row.get("judge_confidence", Float.class)).isEqualTo(0.9f);
        assertThat(row.get("rationale", String.class)).isEqualTo("different cities");
        // pending is not terminal (still awaits resolved/dismissed) -> no resolved_at yet.
        assertThat(row.get("resolved_at", OffsetDateTime.class)).isNull();

        boolean secondDelivery = pairs.recordVerdict(pairId, false, 0.1, "changed my mind");

        assertThat(secondDelivery).isFalse();
        Record unchanged = fetch(pairId);
        assertThat(unchanged.get("status", String.class)).isEqualTo("pending");
        assertThat(unchanged.get("judge_confidence", Float.class)).isEqualTo(0.9f);
        assertThat(unchanged.get("rationale", String.class)).isEqualTo("different cities");
    }

    @Test
    void recordVerdictFalseMarksNotContradictory() {
        UUID job = createJob();
        UUID a = insertFact("bob", "lives_in", "Hamburg");
        UUID b = insertFact("bob", "lives_in", "Cologne");
        UUID pairId = pairs.reserve(List.of(candidateOf("bob", "lives_in", a, b)), job).get(0);

        boolean applied = pairs.recordVerdict(pairId, false, 0.7, "same city, different spelling");

        assertThat(applied).isTrue();
        Record row = fetch(pairId);
        assertThat(row.get("status", String.class)).isEqualTo("not_contradictory");
        // not_contradictory is terminal -> gets a closure timestamp like the other terminal writes.
        assertThat(row.get("resolved_at", OffsetDateTime.class)).isNotNull();
    }

    @Test
    void recordVerdictOnANonInFlightRowReturnsFalse() {
        UUID a = insertFact("alice", "lives_in", "Berlin");
        UUID b = insertFact("alice", "lives_in", "Munich");
        recordContradiction(a, b, "alice", "lives_in", "pending");
        UUID pairId = fetchIdForSubject("alice");

        assertThat(pairs.recordVerdict(pairId, true, 0.5, "x")).isFalse();
        assertThat(fetch(pairId).get("status", String.class)).isEqualTo("pending");
    }

    @Test
    void compensateUndoesExactlyThisTicksReservationsAndLeavesOtherJobsAlone() {
        // A wholly unrelated job whose rows must survive job2's compensation untouched, on both the
        // DELETE leg (fresh, attempts = 1) and the UPDATE leg (re-reserved, attempts = 2) — a missing
        // job_id predicate on either leg would otherwise go unnoticed by this control. Set up and
        // re-reserved fully before job1's own retryable row exists below: reReserve is not scoped to
        // an originating job, it claims the oldest retryable rows overall, so a later-created
        // retryable row must not exist yet or this job's own reReserve call would steal it too.
        UUID otherJob = createJob();
        UUID s2a = insertFact("s2", "lives_in", "A");
        UUID s2b = insertFact("s2", "lives_in", "B");
        UUID otherJobFreshId = pairs.reserve(List.of(candidateOf("s2", "lives_in", s2a, s2b)), otherJob).get(0);
        UUID s2c = insertFact("s2b", "lives_in", "A");
        UUID s2d = insertFact("s2b", "lives_in", "B");
        recordContradiction(s2c, s2d, "s2b", "lives_in", "retryable");
        UUID otherJobReReservedId = fetchIdForSubject("s2b");
        pairs.reReserve(otherJob, 10); // brings otherJobReReservedId to in_flight, attempts = 2
        assertThat(fetch(otherJobReReservedId).get("attempts", Integer.class)).isEqualTo(2);

        UUID job1 = createJob();
        UUID s1a = insertFact("s1", "lives_in", "A");
        UUID s1b = insertFact("s1", "lives_in", "B");
        UUID staleId = pairs.reserve(List.of(candidateOf("s1", "lives_in", s1a, s1b)), job1).get(0);
        pairs.applyAttemptRule(job1, 5);
        assertThat(fetch(staleId).get("status", String.class)).isEqualTo("retryable");

        UUID job2 = createJob();
        UUID s3a = insertFact("s3", "lives_in", "A");
        UUID s3b = insertFact("s3", "lives_in", "B");
        UUID job2Fresh = pairs.reserve(List.of(candidateOf("s3", "lives_in", s3a, s3b)), job2).get(0);
        pairs.reReserve(job2, 10); // re-reserves staleId under job2 (only retryable row left)
        assertThat(fetch(staleId).get("attempts", Integer.class)).isEqualTo(2);
        assertThat(fetch(staleId).get("job_id", UUID.class)).isEqualTo(job2);

        pairs.compensate(job2);

        assertThat(fetch(job2Fresh)).isNull();
        Record reverted = fetch(staleId);
        assertThat(reverted.get("status", String.class)).isEqualTo("retryable");
        assertThat(reverted.get("attempts", Integer.class)).isEqualTo(1);
        // A different job's rows are untouched by job2's compensation, on both legs.
        Record untouchedFresh = fetch(otherJobFreshId);
        assertThat(untouchedFresh.get("status", String.class)).isEqualTo("in_flight");
        assertThat(untouchedFresh.get("job_id", UUID.class)).isEqualTo(otherJob);
        Record untouchedReReserved = fetch(otherJobReReservedId);
        assertThat(untouchedReReserved.get("status", String.class)).isEqualTo("in_flight");
        assertThat(untouchedReReserved.get("attempts", Integer.class)).isEqualTo(2);
        assertThat(untouchedReReserved.get("job_id", UUID.class)).isEqualTo(otherJob);
    }

    @Test
    void supersedeForPredicateClosesOnlyInFlightAndRetryable() {
        String predicate = "affects_predicate";
        Map<String, UUID> idByStatus = new HashMap<>();
        for (String status : List.of("in_flight", "retryable", "pending", "resolved", "dismissed", "deferred")) {
            String subject = "s_" + status;
            UUID a = insertFact(subject, predicate, "A");
            UUID b = insertFact(subject, predicate, "B");
            recordContradiction(a, b, subject, predicate, status);
            idByStatus.put(status, fetchIdForSubject(subject));
        }

        int count = pairs.supersedeForPredicate(predicate);

        assertThat(count).isEqualTo(2);
        assertThat(fetch(idByStatus.get("in_flight")).get("status", String.class)).isEqualTo("superseded");
        assertThat(fetch(idByStatus.get("in_flight")).get("resolved_at", OffsetDateTime.class)).isNotNull();
        assertThat(fetch(idByStatus.get("retryable")).get("status", String.class)).isEqualTo("superseded");
        assertThat(fetch(idByStatus.get("retryable")).get("resolved_at", OffsetDateTime.class)).isNotNull();
        assertThat(fetch(idByStatus.get("pending")).get("status", String.class)).isEqualTo("pending");
        assertThat(fetch(idByStatus.get("resolved")).get("status", String.class)).isEqualTo("resolved");
        assertThat(fetch(idByStatus.get("dismissed")).get("status", String.class)).isEqualTo("dismissed");
        assertThat(fetch(idByStatus.get("deferred")).get("status", String.class)).isEqualTo("deferred");
    }

    @Test
    void autoCloseInactiveSupersedesOnlyPendingRowsWithAnInactiveFact() {
        UUID activeA = insertFact("alice", "lives_in", "Berlin");
        UUID inactiveB = insertPendingFact("alice", "lives_in", "Munich"); // never committed -> not in active_facts
        recordContradiction(activeA, inactiveB, "alice", "lives_in", "pending");
        UUID pendingRowId = fetchIdForSubject("alice");
        // Simulates the judge's own explanation, written earlier by recordVerdict — autoCloseInactive
        // must not overwrite it; overwriting review history is exactly what this class must not do.
        dsl.execute("UPDATE fact_contradictions SET rationale = ? WHERE id = ?",
                "judge: distinct cities, not a spelling variant", pendingRowId);

        UUID deferredA = insertFact("bob", "lives_in", "Hamburg");
        UUID deferredInactiveB = insertPendingFact("bob", "lives_in", "Cologne");
        recordContradiction(deferredA, deferredInactiveB, "bob", "lives_in", "deferred");
        UUID deferredRowId = fetchIdForSubject("bob");

        UUID inFlightA = insertFact("carol", "lives_in", "X");
        UUID inFlightInactiveB = insertPendingFact("carol", "lives_in", "Y");
        recordContradiction(inFlightA, inFlightInactiveB, "carol", "lives_in", "in_flight");
        UUID inFlightRowId = fetchIdForSubject("carol");

        // Control: a pending row whose BOTH facts are committed and active must stay pending. Without
        // this row, an implementation that supersedes every pending row unconditionally (the NOT
        // EXISTS correlation deleted) would still pass count == 1 and the other assertions.
        UUID dave1 = insertFact("dave", "lives_in", "M");
        UUID dave2 = insertFact("dave", "lives_in", "N");
        recordContradiction(dave1, dave2, "dave", "lives_in", "pending");
        UUID stillPendingRowId = fetchIdForSubject("dave");

        int count = pairs.autoCloseInactive();

        assertThat(count).isEqualTo(1);
        Record closed = fetch(pendingRowId);
        assertThat(closed.get("status", String.class)).isEqualTo("superseded");
        // The judge's rationale survives the machine close untouched.
        assertThat(closed.get("rationale", String.class)).isEqualTo("judge: distinct cities, not a spelling variant");
        assertThat(closed.get("resolved_at", OffsetDateTime.class)).isNotNull();
        assertThat(fetch(deferredRowId).get("status", String.class)).isEqualTo("deferred");
        assertThat(fetch(inFlightRowId).get("status", String.class)).isEqualTo("in_flight");
        assertThat(fetch(stillPendingRowId).get("status", String.class)).isEqualTo("pending");
    }

    @Test
    void inFlightIdsOfJobReturnsOnlyThatJobsInFlightRows() {
        UUID job1 = createJob();
        UUID a = insertFact("alice", "lives_in", "Berlin");
        UUID b = insertFact("alice", "lives_in", "Munich");
        List<UUID> job1Ids = pairs.reserve(List.of(candidateOf("alice", "lives_in", a, b)), job1);

        UUID job2 = createJob();
        UUID c = insertFact("bob", "lives_in", "Hamburg");
        UUID d = insertFact("bob", "lives_in", "Cologne");
        pairs.reserve(List.of(candidateOf("bob", "lives_in", c, d)), job2);

        UUID e = insertFact("carol", "lives_in", "X");
        UUID f = insertFact("carol", "lives_in", "Y");
        recordContradiction(e, f, "carol", "lives_in", "pending"); // no job_id, not in_flight

        assertThat(pairs.inFlightIdsOfJob(job1)).containsExactlyElementsOf(job1Ids);
    }

    private UUID createJob() {
        return dsl.fetchOne("""
                INSERT INTO contradiction_jobs (correlation_id, kind, item_count)
                VALUES (?, 'pairs', 1)
                RETURNING id
                """, UUID.randomUUID()).get("id", UUID.class);
    }

    private Record fetch(UUID id) {
        return dsl.fetchOne("SELECT * FROM fact_contradictions WHERE id = ?", id);
    }

    private UUID fetchIdForSubject(String subject) {
        return dsl.fetchOne("SELECT id FROM fact_contradictions WHERE subject = ?", subject).get("id", UUID.class);
    }

    private int countRows() {
        return dsl.fetchOne("SELECT count(*) AS c FROM fact_contradictions").get("c", Integer.class);
    }

    private int countByStatus(String status) {
        return dsl.fetchOne("SELECT count(*) AS c FROM fact_contradictions WHERE status = ?", status)
                .get("c", Integer.class);
    }
}
