package com.hivemem.contradiction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Pins {@link ContradictionService}'s contract: webhook verdict application never strands
 * unanswered items, never throws on an unknown/duplicate delivery, and the human resolution paths
 * (resolve, setCardinality, list) do exactly what the review queue needs and nothing more.
 */
@ExtendWith(OutputCaptureExtension.class)
class ContradictionServiceIT extends ContradictionITSupport {

    @Autowired DSLContext dsl;
    @Autowired ContradictionService service;
    @Autowired ContradictionJobRepository jobs;
    @Autowired ContradictionRepository pairs;
    @Autowired PredicateCardinalityRepository cardinality;

    @DynamicPropertySource
    static void serviceProps(DynamicPropertyRegistry r) {
        r.add("hivemem.contradiction.enabled", () -> "true");
        r.add("hivemem.queen.enabled", () -> "true");
        r.add("hivemem.queen.contradiction-webhook-token", () -> "test-contradiction-webhook-token");
    }

    // ---- resolve: fact_a / fact_b -------------------------------------------------------

    @Test
    void resolvingInvalidatesTheLoserThroughTheOpLoggedPath() {
        UUID factA = insertFact("alice", "lives_in", "berlin");
        UUID factB = insertFact("alice", "lives_in", "hamburg");
        UUID pairId = insertPair(factA, factB, "alice", "lives_in", "pending");

        Map<String, Object> result = service.resolve(pairId, "fact_a", "berlin is current");

        assertThat(result).containsEntry("status", "resolved").containsEntry("kept", "fact_a");
        assertThat(factValidUntil(factB)).isNotNull();
        assertThat(factValidUntil(factA)).isNull();
        assertThat(countOpsLog("kg_invalidate", factB)).isEqualTo(1);
        assertThat(pairStatus(pairId)).isEqualTo("resolved");
    }

    /**
     * The loser was already invalidated by something else (a race, or a previous partial resolve)
     * before this call: {@code kgInvalidate} short-circuits without appending a second op, but the
     * pair must still resolve cleanly. Asserts the op COUNT (not just the outcome) since a naive
     * "resolve always writes an op" implementation would pass the outcome-only check while silently
     * double-logging.
     */
    @Test
    void resolvingAnAlreadyInvalidLoserIsIdempotentAndAddsNoSecondOpLogEntry(CapturedOutput output) {
        UUID factA = insertFact("bob", "lives_in", "berlin");
        UUID factB = insertFact("bob", "lives_in", "hamburg");
        dsl.execute("UPDATE facts SET valid_until = now() - interval '1 minute' WHERE id = ?", factB);
        UUID pairId = insertPair(factA, factB, "bob", "lives_in", "pending");

        Map<String, Object> result = service.resolve(pairId, "fact_a", null);

        assertThat(result).containsEntry("status", "resolved");
        assertThat(countOpsLog("kg_invalidate", factB)).isZero();
        assertThat(factValidUntil(factA)).isNull();
        assertThat(pairStatus(pairId)).isEqualTo("resolved");
        // The no-op invalidation must not be silent - Task 16 will want this log line.
        assertThat(output.getOut() + output.getErr()).contains("kgInvalidate was a no-op");
    }

    // ---- resolve: both / requeue ---------------------------------------------------------

    @Test
    void bothDismissesWithZeroFactMutationsAndReturnsThePredicateHintWithoutReclassifying() {
        UUID factA = insertFact("carol", "key_term", "hiking");
        UUID factB = insertFact("carol", "key_term", "cycling");
        UUID pairId = insertPair(factA, factB, "carol", "key_term", "pending");

        Map<String, Object> result = service.resolve(pairId, "both", "both are true key terms");

        assertThat(result).containsEntry("status", "dismissed");
        assertThat((String) result.get("hint")).contains("key_term").contains("multi_valued");
        assertThat(pairStatus(pairId)).isEqualTo("dismissed");
        assertThat(factValidUntil(factA)).isNull();
        assertThat(factValidUntil(factB)).isNull();
        assertThat(dsl.fetchOne("SELECT count(*) AS c FROM predicate_cardinality WHERE predicate = 'key_term'")
                .get("c", Integer.class)).isZero();
    }

    @Test
    void requeueReturnsADeferredRowToRetryableWithAttemptsReset() {
        UUID factA = insertFact("dave", "lives_in", "berlin");
        UUID factB = insertFact("dave", "lives_in", "hamburg");
        UUID pairId = insertPair(factA, factB, "dave", "lives_in", "deferred");
        dsl.execute("UPDATE fact_contradictions SET attempts = 3 WHERE id = ?", pairId);

        Map<String, Object> result = service.resolve(pairId, "requeue", null);

        assertThat(result).containsEntry("status", "retryable");
        Record row = dsl.fetchOne("SELECT status, attempts FROM fact_contradictions WHERE id = ?", pairId);
        assertThat(row.get("status", String.class)).isEqualTo("retryable");
        assertThat(row.get("attempts", Integer.class)).isEqualTo(0);
    }

    /**
     * A predicate flips {@code multi_valued} (by a judge verdict or a human override) after this
     * particular pair was dispatched and landed on {@code deferred}; that predicate's other still-open
     * rows would already have been superseded, but a {@code deferred} row deliberately sits outside
     * that sweep so a human can still inspect it. Requeueing it anyway would re-dispatch a pair for a
     * predicate the judge's own prompt assumes is single-valued — the guard must reject this and
     * leave the pair exactly as it was.
     */
    @Test
    void requeueingADeferredPairForAKnownMultiValuedPredicateIsRejectedAndTouchesNothing() {
        UUID factA = insertFact("gary", "key_term_deferred", "hiking");
        UUID factB = insertFact("gary", "key_term_deferred", "cycling");
        UUID pairId = insertPair(factA, factB, "gary", "key_term_deferred", "deferred");
        dsl.execute("UPDATE fact_contradictions SET attempts = 3 WHERE id = ?", pairId);
        service.setCardinality("key_term_deferred", "multi_valued", "obviously repeated key terms");

        assertThatThrownBy(() -> service.resolve(pairId, "requeue", null))
                .isInstanceOf(IllegalStateException.class);

        Record row = dsl.fetchOne("SELECT status, attempts FROM fact_contradictions WHERE id = ?", pairId);
        assertThat(row.get("status", String.class)).isEqualTo("deferred");
        assertThat(row.get("attempts", Integer.class)).isEqualTo(3);
    }

    // ---- resolve: status guard -------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"resolved", "dismissed", "superseded", "not_contradictory", "in_flight", "retryable"})
    void nonActionableStatusesAreRejected(String nonActionableStatus) {
        UUID factA = insertFact("erin", "lives_in", "berlin");
        UUID factB = insertFact("erin", "lives_in", "hamburg");
        UUID pairId = insertPair(factA, factB, "erin", "lives_in", nonActionableStatus);

        assertThatThrownBy(() -> service.resolve(pairId, "requeue", null))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * The allow-list bug this guards: {@code in_flight} and {@code retryable} both pass the old
     * deny-list (they are not among the four historical "terminal" statuses), yet a human must
     * never resolve a pair the judge is still working, or one already queued for re-dispatch.
     * {@code fact_a}/{@code fact_b} is the dangerous case specifically because it mutates the graph
     * — this asserts that mutation never happens for a rejected call, not just that the exception
     * is thrown.
     */
    @ParameterizedTest
    @ValueSource(strings = {"in_flight", "retryable"})
    void resolvingAnInFlightOrRetryablePairAsFactAIsRejectedAndTouchesNothing(String status) {
        UUID factA = insertFact("wendy", "lives_in", "berlin");
        UUID factB = insertFact("wendy", "lives_in", "hamburg");
        UUID pairId = insertPair(factA, factB, "wendy", "lives_in", status);

        assertThatThrownBy(() -> service.resolve(pairId, "fact_a", null))
                .isInstanceOf(IllegalStateException.class);

        assertThat(factValidUntil(factB)).isNull();
        assertThat(countOpsLog("kg_invalidate", factB)).isZero();
        assertThat(pairStatus(pairId)).isEqualTo(status);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "deferred"})
    void pendingAndDeferredAreBothActionable(String actionableStatus) {
        UUID factA = insertFact("frank", "lives_in", "berlin");
        UUID factB = insertFact("frank", "lives_in", "hamburg");
        UUID pairId = insertPair(factA, factB, "frank", "lives_in", actionableStatus);

        Map<String, Object> result = service.resolve(pairId, "requeue", null);

        assertThat(result).containsEntry("status", "retryable");
    }

    @Test
    void resolveRejectsAnUnknownId() {
        assertThatThrownBy(() -> service.resolve(UUID.randomUUID(), "requeue", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Exercises {@link ContradictionService#resolve}'s own {@code default -> throw} arm — a
     * guard that is separate from, and was previously untested alongside,
     * {@code ResolveContradictionToolHandler}'s handler-level {@code keep} enum check. Neither
     * guard's test proves anything about the other: the handler test never reaches the service
     * (it throws first), and this one calls the service directly, bypassing the handler entirely.
     */
    @Test
    void resolveRejectsAnUnknownKeepValue() {
        UUID factA = insertFact("oscar", "lives_in", "berlin");
        UUID factB = insertFact("oscar", "lives_in", "hamburg");
        UUID pairId = insertPair(factA, factB, "oscar", "lives_in", "pending");

        assertThatThrownBy(() -> service.resolve(pairId, "fact_c", null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(pairStatus(pairId)).isEqualTo("pending");
        assertThat(countOpsLog("kg_invalidate", factA)).isZero();
        assertThat(countOpsLog("kg_invalidate", factB)).isZero();
    }

    // ---- applyPairVerdicts -----------------------------------------------------------------

    /**
     * The subtle one: a job dispatches three pairs, the judge only answers two of them. The
     * answered pairs must land on their judged status, the unanswered one must follow the attempt
     * rule (not strand in_flight), and the job itself must still reach 'done' - a done job is never
     * revisited by the reconcile sweep, so this is the only chance for that third row to move.
     */
    @Test
    void partialVerdictSetRecordsAnsweredPairsAndAppliesAttemptRuleToTheRest() {
        UUID job = jobs.create(UUID.randomUUID(), "pairs", 3);
        jobs.attachRunId(job, "run-partial");
        UUID factA1 = insertFact("gina", "lives_in", "berlin");
        UUID factB1 = insertFact("gina", "lives_in", "hamburg");
        UUID factA2 = insertFact("hank", "lives_in", "koeln");
        UUID factB2 = insertFact("hank", "lives_in", "bonn");
        UUID factA3 = insertFact("iris", "lives_in", "mainz");
        UUID factB3 = insertFact("iris", "lives_in", "wiesbaden");
        UUID pair1 = pairs.reserve(List.of(candidateOf("gina", "lives_in", factA1, factB1)), job).get(0);
        UUID pair2 = pairs.reserve(List.of(candidateOf("hank", "lives_in", factA2, factB2)), job).get(0);
        UUID pair3 = pairs.reserve(List.of(candidateOf("iris", "lives_in", factA3, factB3)), job).get(0);

        service.applyPairVerdicts("run-partial", "done", List.of(
                new PairVerdicts.Verdict(pair1, true, 0.9, "genuinely conflicting"),
                new PairVerdicts.Verdict(pair2, false, 0.95, "actually a duplicate")));

        assertThat(pairStatus(pair1)).isEqualTo("pending");
        assertThat(pairStatus(pair2)).isEqualTo("not_contradictory");
        assertThat(pairStatus(pair3)).isEqualTo("retryable");
        assertThat(jobStatus(job)).isEqualTo("done");
    }

    /**
     * Required behaviour 7 is three things, not two: fail the job, apply the attempt rule, AND log
     * the raw status. That log line is the entire evidence trail Task 16 needs to eventually
     * classify a non-done callback (e.g. a missing model_purpose routing rule on Vistierie's side)
     * — asserting only the DB state would leave that third requirement unpinned.
     */
    @Test
    void nonDoneRunStatusFailsTheJobAndAppliesTheAttemptRule(CapturedOutput output) {
        UUID job = jobs.create(UUID.randomUUID(), "pairs", 1);
        jobs.attachRunId(job, "run-failed");
        UUID factA = insertFact("jack", "lives_in", "berlin");
        UUID factB = insertFact("jack", "lives_in", "hamburg");
        UUID pairId = pairs.reserve(List.of(candidateOf("jack", "lives_in", factA, factB)), job).get(0);

        service.applyPairVerdicts("run-failed", "error", null);

        assertThat(jobStatus(job)).isEqualTo("failed");
        assertThat(pairStatus(pairId)).isEqualTo("retryable");
        assertThat(output.getOut() + output.getErr())
                .contains("run-failed")
                .contains("status='error'");
    }

    /**
     * The pair named in the verdict was reserved by a DIFFERENT job (still legitimately in_flight
     * there), not by the job this callback answers. Without the inFlightIdsOfJob(jobId)-membership
     * guard, recordVerdict's own SQL has no notion of "which job" and would happily write it anyway
     * — corrupting the other job's still-open row. A pair id with no row at all would pass this same
     * assertion for the wrong reason (recordVerdict simply finds no matching row), so the fixture
     * deliberately gives the pair a real, live in_flight row under a job that must stay untouched.
     * Mirrors {@link #cardinalityVerdictForAPredicateNotDispatchedByThisJobIsIgnored}.
     */
    @Test
    void pairVerdictForAPairNotDispatchedByThisJobIsIgnored() {
        UUID otherJob = jobs.create(UUID.randomUUID(), "pairs", 1);
        UUID otherFactA = insertFact("zack", "lives_in", "berlin");
        UUID otherFactB = insertFact("zack", "lives_in", "hamburg");
        UUID otherPair = pairs.reserve(List.of(candidateOf("zack", "lives_in", otherFactA, otherFactB)), otherJob)
                .get(0);

        UUID job = jobs.create(UUID.randomUUID(), "pairs", 1);
        jobs.attachRunId(job, "run-pair-ignore");
        UUID ownFactA = insertFact("yolanda", "lives_in", "koeln");
        UUID ownFactB = insertFact("yolanda", "lives_in", "bonn");
        UUID ownPair = pairs.reserve(List.of(candidateOf("yolanda", "lives_in", ownFactA, ownFactB)), job).get(0);

        service.applyPairVerdicts("run-pair-ignore", "done", List.of(
                new PairVerdicts.Verdict(otherPair, true, 0.9, "should be ignored - wrong job")));

        Record other = dsl.fetchOne("SELECT status, job_id FROM fact_contradictions WHERE id = ?", otherPair);
        assertThat(other.get("status", String.class)).isEqualTo("in_flight");
        assertThat(other.get("job_id", UUID.class)).isEqualTo(otherJob);
        // The job's own dispatched-but-unanswered pair must still follow the attempt rule.
        assertThat(pairStatus(ownPair)).isEqualTo("retryable");
        assertThat(jobStatus(job)).isEqualTo("done");
    }

    /**
     * A verdict earlier in the list fails to apply (duplicate/unknown pair id); a later, valid
     * verdict in the SAME batch must still be applied. Guards against a naive loop that returns (or
     * breaks) on the first unrecorded verdict instead of just logging and continuing.
     */
    @Test
    void aFailedVerdictDoesNotAbortLaterVerdictsInTheSameBatch() {
        UUID job = jobs.create(UUID.randomUUID(), "pairs", 1);
        jobs.attachRunId(job, "run-mixed");
        UUID factA = insertFact("victor", "lives_in", "berlin");
        UUID factB = insertFact("victor", "lives_in", "hamburg");
        UUID realPair = pairs.reserve(List.of(candidateOf("victor", "lives_in", factA, factB)), job).get(0);
        UUID bogusPairId = UUID.randomUUID(); // never reserved -> recordVerdict returns false for it

        service.applyPairVerdicts("run-mixed", "done", List.of(
                new PairVerdicts.Verdict(bogusPairId, true, 0.5, "unknown pair, should be skipped"),
                new PairVerdicts.Verdict(realPair, true, 0.9, "should still be applied")));

        assertThat(pairStatus(realPair)).isEqualTo("pending");
    }

    @Test
    void unknownRunIdIsANoOp() {
        service.applyPairVerdicts("no-such-run", "done", List.of());
        // No exception, and nothing to assert against - the point is it never throws.
    }

    /**
     * A malformed callback body ({@code "verdicts": [null]}) deserializes to a list containing a
     * null element rather than failing to parse. Without a null guard, {@code v.pair_id()} would
     * NPE past the already-successful {@code claim()} - the retry Task 13 would send after catching
     * that exception is then swallowed as a duplicate delivery, and the job silently waits for the
     * stale-job sweep instead of being handled by this call. The real, valid verdict alongside it
     * must still apply.
     */
    @Test
    void aNullVerdictElementIsSkippedNotThrown() {
        UUID job = jobs.create(UUID.randomUUID(), "pairs", 1);
        jobs.attachRunId(job, "run-null-verdict");
        UUID factA = insertFact("yara", "lives_in", "berlin");
        UUID factB = insertFact("yara", "lives_in", "hamburg");
        UUID realPair = pairs.reserve(List.of(candidateOf("yara", "lives_in", factA, factB)), job).get(0);

        List<PairVerdicts.Verdict> verdicts = new java.util.ArrayList<>();
        verdicts.add(null);
        verdicts.add(new PairVerdicts.Verdict(realPair, true, 0.9, "should still be applied"));

        service.applyPairVerdicts("run-null-verdict", "done", verdicts);

        assertThat(pairStatus(realPair)).isEqualTo("pending");
        assertThat(jobStatus(job)).isEqualTo("done");
    }

    @Test
    void duplicateDeliveryIsANoOp() {
        UUID job = jobs.create(UUID.randomUUID(), "pairs", 1);
        jobs.attachRunId(job, "run-dup");
        UUID factA = insertFact("kate", "lives_in", "berlin");
        UUID factB = insertFact("kate", "lives_in", "hamburg");
        UUID pairId = pairs.reserve(List.of(candidateOf("kate", "lives_in", factA, factB)), job).get(0);
        jobs.claim(job); // simulates the first delivery already owning this job

        service.applyPairVerdicts("run-dup", "done", List.of(
                new PairVerdicts.Verdict(pairId, true, 0.9, "should not apply")));

        assertThat(jobStatus(job)).isEqualTo("processing");
        assertThat(pairStatus(pairId)).isEqualTo("in_flight");
    }

    // ---- applyCardinalityVerdicts ------------------------------------------------------------

    /**
     * The pairs and cardinality methods are copy-paste symmetric today; without a test on this
     * twin, an edit to one non-done branch could silently drift from the other. Mirrors {@link
     * #nonDoneRunStatusFailsTheJobAndAppliesTheAttemptRule}.
     */
    @Test
    void nonDoneRunStatusFailsTheCardinalityJobAndAppliesTheAttemptRule(CapturedOutput output) {
        UUID job = jobs.create(UUID.randomUUID(), "cardinality", 1);
        jobs.attachRunId(job, "run-card-failed");
        cardinality.reserve(List.of("card_error_predicate"), job);

        service.applyCardinalityVerdicts("run-card-failed", "error", null);

        assertThat(jobStatus(job)).isEqualTo("failed");
        assertThat(cardinalityRow("card_error_predicate").get("status", String.class)).isEqualTo("retryable");
        assertThat(output.getOut() + output.getErr())
                .contains("run-card-failed")
                .contains("status='error'");
    }

    /** Mirrors {@link #aNullVerdictElementIsSkippedNotThrown} for the cardinality twin. */
    @Test
    void aNullCardinalityVerdictElementIsSkippedNotThrown() {
        UUID job = jobs.create(UUID.randomUUID(), "cardinality", 1);
        jobs.attachRunId(job, "run-card-null-verdict");
        cardinality.reserve(List.of("card_null_predicate"), job);

        List<CardinalityVerdicts.Verdict> verdicts = new java.util.ArrayList<>();
        verdicts.add(null);
        verdicts.add(new CardinalityVerdicts.Verdict("card_null_predicate", "single_valued", 0.9, "fine"));

        service.applyCardinalityVerdicts("run-card-null-verdict", "done", verdicts);

        assertThat(cardinalityRow("card_null_predicate").get("status", String.class)).isEqualTo("decided");
        assertThat(jobStatus(job)).isEqualTo("done");
    }

    /**
     * The predicate named in the verdict was reserved by a DIFFERENT job (still legitimately
     * in_flight there), not by the job this callback answers. Without the
     * predicatesOfJob(jobId)-membership guard, recordVerdict's own SQL has no notion of "which job"
     * and would happily decide it anyway - corrupting the other job's still-open row. A predicate
     * with no row at all would pass this same assertion for the wrong reason (recordVerdict simply
     * finds no matching row), so the fixture deliberately gives the predicate a real, live row
     * under a job that must stay untouched.
     */
    @Test
    void cardinalityVerdictForAPredicateNotDispatchedByThisJobIsIgnored() {
        UUID otherJob = jobs.create(UUID.randomUUID(), "cardinality", 1);
        cardinality.reserve(List.of("owned_by_other_job"), otherJob);

        UUID job = jobs.create(UUID.randomUUID(), "cardinality", 1);
        jobs.attachRunId(job, "run-card-ignore");
        cardinality.reserve(List.of("owned_by_this_job"), job);

        service.applyCardinalityVerdicts("run-card-ignore", "done", List.of(
                new CardinalityVerdicts.Verdict("owned_by_other_job", "single_valued", 0.9, "should be ignored")));

        Record other = cardinalityRow("owned_by_other_job");
        assertThat(other.get("status", String.class)).isEqualTo("in_flight");
        assertThat(other.get("job_id", UUID.class)).isEqualTo(otherJob);
        // The job's own dispatched-but-unanswered predicate must still follow the attempt rule.
        assertThat(cardinalityRow("owned_by_this_job").get("status", String.class)).isEqualTo("retryable");
        assertThat(jobStatus(job)).isEqualTo("done");
    }

    /**
     * A multi_valued verdict must close out that predicate's still-open pairs so they stop being
     * re-dispatched, but must leave a pending row (a human is already looking at it) and an
     * unrelated predicate's pairs untouched - proving the scope is exactly "this predicate's
     * in_flight/retryable rows", not "everything".
     */
    @Test
    void multiValuedVerdictSupersedesThatPredicatesNonTerminalPairsOnly() {
        UUID job = jobs.create(UUID.randomUUID(), "cardinality", 1);
        jobs.attachRunId(job, "run-multi");
        cardinality.reserve(List.of("key_term"), job);

        UUID fa1 = insertFact("liam", "key_term", "hiking");
        UUID fb1 = insertFact("liam", "key_term", "cycling");
        UUID inFlightPair = insertPair(fa1, fb1, "liam", "key_term", "in_flight");

        UUID fa2 = insertFact("mia", "key_term", "reading");
        UUID fb2 = insertFact("mia", "key_term", "painting");
        UUID retryablePair = insertPair(fa2, fb2, "mia", "key_term", "retryable");

        UUID fa3 = insertFact("noah", "key_term", "chess");
        UUID fb3 = insertFact("noah", "key_term", "poker");
        UUID pendingPair = insertPair(fa3, fb3, "noah", "key_term", "pending");

        UUID fa4 = insertFact("olive", "lives_in", "berlin");
        UUID fb4 = insertFact("olive", "lives_in", "hamburg");
        UUID unrelatedPair = insertPair(fa4, fb4, "olive", "lives_in", "in_flight");

        service.applyCardinalityVerdicts("run-multi", "done", List.of(
                new CardinalityVerdicts.Verdict("key_term", "multi_valued", 0.92, "many per cell")));

        assertThat(pairStatus(inFlightPair)).isEqualTo("superseded");
        assertThat(pairStatus(retryablePair)).isEqualTo("superseded");
        assertThat(pairStatus(pendingPair)).isEqualTo("pending");
        assertThat(pairStatus(unrelatedPair)).isEqualTo("in_flight");
        assertThat(cardinalityRow("key_term").get("cardinality", String.class)).isEqualTo("multi_valued");
    }

    // ---- setCardinality ----------------------------------------------------------------------

    @Test
    void setCardinalityWritesAHumanRowThatALaterJudgeVerdictCannotOverwrite() {
        Map<String, Object> result = service.setCardinality("owner", "single_valued", "a cell has one owner");

        assertThat(result).containsEntry("cardinality", "single_valued").containsEntry("decided_by", "human");

        boolean laterJudgeApplied = cardinality.recordVerdict("owner", "multi_valued", 0.99, "judge disagrees");

        assertThat(laterJudgeApplied).isFalse();
        Record row = cardinalityRow("owner");
        assertThat(row.get("decided_by", String.class)).isEqualTo("human");
        assertThat(row.get("cardinality", String.class)).isEqualTo("single_valued");
    }

    @Test
    void setCardinalityMultiValuedSupersedesOpenPairsAndReportsTheCount() {
        UUID fa = insertFact("peter", "key_term_human", "hiking");
        UUID fb = insertFact("peter", "key_term_human", "cycling");
        UUID pairId = insertPair(fa, fb, "peter", "key_term_human", "in_flight");

        Map<String, Object> result = service.setCardinality("key_term_human", "multi_valued", "obviously repeated");

        assertThat(result).containsEntry("superseded", 1);
        assertThat(pairStatus(pairId)).isEqualTo("superseded");
    }

    // ---- list --------------------------------------------------------------------------------

    @Test
    void listFiltersByStatusAndExcludesPairsWhoseFactWentInactiveOnlyForPending() {
        UUID activeA = insertFact("quinn", "lives_in", "berlin");
        UUID activeB = insertFact("quinn", "lives_in", "hamburg");
        UUID visiblePendingPair = insertPair(activeA, activeB, "quinn", "lives_in", "pending");

        UUID stillActive = insertFact("rex", "lives_in", "berlin");
        UUID wentInactive = insertFact("rex", "lives_in", "hamburg");
        dsl.execute("UPDATE facts SET valid_until = now() - interval '1 minute' WHERE id = ?", wentInactive);
        UUID hiddenPendingPair = insertPair(stillActive, wentInactive, "rex", "lives_in", "pending");

        UUID deferredA = insertFact("sara", "lives_in", "berlin");
        UUID deferredBInactive = insertFact("sara", "lives_in", "hamburg");
        dsl.execute("UPDATE facts SET valid_until = now() - interval '1 minute' WHERE id = ?", deferredBInactive);
        UUID deferredPairDespiteInactiveFact =
                insertPair(deferredA, deferredBInactive, "sara", "lives_in", "deferred");

        List<Map<String, Object>> pendingView = service.list("pending", null, null);
        assertThat(pendingView).extracting(m -> m.get("id"))
                .contains(visiblePendingPair.toString())
                .doesNotContain(hiddenPendingPair.toString());

        List<Map<String, Object>> deferredView = service.list("deferred", null, null);
        assertThat(deferredView).extracting(m -> m.get("id"))
                .contains(deferredPairDespiteInactiveFact.toString());
    }

    @Test
    void listFiltersBySubject() {
        UUID a1 = insertFact("tara", "lives_in", "berlin");
        UUID b1 = insertFact("tara", "lives_in", "hamburg");
        UUID tarasPair = insertPair(a1, b1, "tara", "lives_in", "pending");

        UUID a2 = insertFact("uma", "lives_in", "berlin");
        UUID b2 = insertFact("uma", "lives_in", "hamburg");
        insertPair(a2, b2, "uma", "lives_in", "pending");

        List<Map<String, Object>> filtered = service.list("pending", "tara", null);

        assertThat(filtered).extracting(m -> m.get("id")).containsExactly(tarasPair.toString());
    }

    /**
     * A negative limit is a Postgres syntax error at the LIMIT clause if passed through unclamped;
     * this proves the call succeeds (does not throw) rather than merely trusting the clamp exists.
     */
    @Test
    void listClampsANegativeLimitInsteadOfFailing() {
        UUID a = insertFact("xena", "lives_in", "berlin");
        UUID b = insertFact("xena", "lives_in", "hamburg");
        insertPair(a, b, "xena", "lives_in", "pending");

        List<Map<String, Object>> result = service.list("pending", "xena", -5);

        assertThat(result).isNotEmpty();
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** Overload returning the generated id, since the shared helper in ITSupport returns void. */
    private UUID insertPair(UUID factA, UUID factB, String subject, String predicate, String status) {
        return dsl.fetchOne("""
                INSERT INTO fact_contradictions (fact_a, fact_b, subject, predicate, status)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """, factA, factB, subject, predicate, status).get("id", UUID.class);
    }

    private java.time.OffsetDateTime factValidUntil(UUID factId) {
        return dsl.fetchOne("SELECT valid_until FROM facts WHERE id = ?", factId)
                .get("valid_until", java.time.OffsetDateTime.class);
    }

    private long countOpsLog(String opType, UUID factId) {
        return dsl.fetchOne("""
                SELECT count(*) AS c FROM ops_log
                WHERE op_type = ? AND payload ->> 'fact_id' = ?
                """, opType, factId.toString()).get("c", Long.class);
    }

    private String pairStatus(UUID pairId) {
        return dsl.fetchOne("SELECT status FROM fact_contradictions WHERE id = ?", pairId)
                .get("status", String.class);
    }

    private String jobStatus(UUID jobId) {
        return dsl.fetchOne("SELECT status FROM contradiction_jobs WHERE id = ?", jobId)
                .get("status", String.class);
    }

    private Record cardinalityRow(String predicate) {
        return dsl.fetchOne("SELECT * FROM predicate_cardinality WHERE predicate = ?", predicate);
    }
}
