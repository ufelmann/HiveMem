package com.hivemem.contradiction;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence for {@code fact_contradictions} — Stage B of contradiction detection, the pair-state
 * store. Owns reserving candidate pairs before dispatch, re-reserving failed ones, recording judge
 * verdicts, and the cleanup paths that close rows a human will never get to see.
 *
 * <p>A row is never deleted on failure. The row <em>is</em> the attempt counter: deleting it and
 * letting {@link ContradictionCandidateRepository#findUnjudged} rediscover the pair would mint a
 * fresh row at {@code attempts = 1} on every tick, so the ceiling in {@link #applyAttemptRule}
 * would never be reached — a pair the judge never answers would silently burn a slot in the daily
 * dispatch run forever instead of eventually landing on {@code deferred} for a human to inspect.
 * The one exception is {@link #compensate}, which deletes exactly the rows a declined Vistierie run
 * never actually reserved — see its Javadoc.
 *
 * <p>Lifecycle (mirrors {@link PredicateCardinalityRepository}'s shape, one stage further):
 * {@code in_flight} → {@code pending} (judge confirmed) or {@code not_contradictory} (judge
 * cleared) or {@code retryable} (job failed); {@code retryable} → {@code in_flight} (re-reserved,
 * {@link #reReserve}) or {@code deferred} (attempt ceiling, {@link #applyAttemptRule}); {@code
 * pending} → {@code resolved} (human picked a winner) or {@code dismissed} (human: legitimately
 * multi-valued); {@code in_flight}/{@code retryable} → {@code superseded} (machine cleanup, {@link
 * #supersedeForPredicate}, {@link #autoCloseInactive}); {@code deferred} → {@code retryable}
 * (human requeue). {@code attempts} increments at re-dispatch ({@link #reReserve}), not at failure,
 * so the ceiling is reached after exactly {@code maxAttempts} dispatches, exactly as {@link
 * PredicateCardinalityRepository}'s Javadoc explains for its own {@code attempts} column.
 */
@Repository
public class ContradictionRepository {

    private final DSLContext dsl;

    public ContradictionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Reserve candidate pairs for a newly dispatched job: insert {@code in_flight} rows with {@code
     * attempts = 1}, {@code job_id}, and a {@code suggested_keep} computed by {@link
     * ContradictionWinnerSelector#suggestKeep}. Idempotent against a pair already recorded in
     * either column order via {@code ON CONFLICT (LEAST(fact_a, fact_b), GREATEST(fact_a, fact_b))
     * DO NOTHING} — the unique index enforces that order-independence, so a plain INSERT would throw
     * the moment two ticks raced (or a stale candidate query re-offered an already-recorded pair).
     *
     * <p>One INSERT per candidate, not a single multi-row {@code unnest} statement (contrast {@link
     * PredicateCardinalityRepository#reserve}, which unnests one column). That table only varies
     * {@code predicate} across rows with every other column fixed; here {@code fact_a}, {@code
     * fact_b}, {@code subject}, {@code predicate} and {@code suggested_keep} all vary per row, and
     * {@code suggested_keep} can be {@code NULL}. A five-array positional zip via {@code unnest} is
     * exactly the kind of construct that silently misaligns rows if any one of the five arrays is
     * ever built out of step with the others, and this codebase has no existing precedent for
     * zipping multiple arrays this way. A per-row {@code RETURNING id} also answers the caller's
     * actual question directly — "was this exact candidate inserted" — without needing to re-derive
     * which of {@code n} candidates a returned id belongs to.
     *
     * <p>Not wrapped in {@code @Transactional}: a crash mid-loop is recovered the same way a crash
     * anywhere else in a dispatch tick is — by {@link ContradictionJobRepository}'s stale-job sweep,
     * which reconciles a job that never got a terminal {@code done}/{@code failed} write. Wrapping
     * this loop would buy no real safety, since a crash after this method commits but before the job
     * is actually dispatched needs that same sweep regardless of whether the reservations themselves
     * were transactional.
     *
     * @return the ids of the rows actually inserted, in the order their candidates were given
     */
    public List<UUID> reserve(List<ContradictionCandidate> candidates, UUID jobId) {
        List<UUID> inserted = new ArrayList<>();
        for (ContradictionCandidate c : candidates) {
            UUID suggestedKeep = ContradictionWinnerSelector.suggestKeep(c);
            Record r = dsl.fetchOne("""
                    INSERT INTO fact_contradictions
                        (fact_a, fact_b, subject, predicate, suggested_keep, job_id, status, attempts)
                    VALUES (?, ?, ?, ?, ?, ?, 'in_flight', 1)
                    ON CONFLICT (LEAST(fact_a, fact_b), GREATEST(fact_a, fact_b)) DO NOTHING
                    RETURNING id
                    """, c.a().factId(), c.b().factId(), c.subject(), c.predicate(), suggestedKeep, jobId);
            if (r != null) {
                inserted.add(r.get("id", UUID.class));
            }
        }
        return inserted;
    }

    /**
     * Re-reserve up to {@code batchSize} pairs that failed a previous job: bump {@code attempts} and
     * move back to {@code in_flight} under the new job id, oldest-{@code detected_at}-first. The cap
     * is mandatory, not a tuning knob: without it, two failed 25-pair jobs would hand the next
     * dispatch a 50-pair payload, silently doubling the batch size the rest of the pipeline was
     * sized for. Rows past the cap are left {@code retryable} for the next tick.
     *
     * <p>The {@code status = 'retryable'} guard is repeated on the outer UPDATE, not left only in
     * the {@code id IN (...)} subquery: under READ COMMITTED, a writer that blocks on a row lock
     * re-evaluates only the outer quals against the already-updated tuple (EvalPlanQual), not the
     * subquery. An outer clause of {@code id IN (...)} alone is still true after a concurrent writer
     * (e.g. {@link #supersedeForPredicate}, which can run from the webhook path in the same JVM as
     * this scheduler tick) moves the row to {@code superseded} between the subquery's snapshot and
     * this UPDATE taking the lock — the id did not change, so the row would be silently resurrected
     * to {@code in_flight} with a doubled attempt count and a stolen {@code job_id}. Repeating the
     * guard on the outer WHERE makes the recheck see the row's current status and skip it, exactly
     * as {@link PredicateCardinalityRepository#reReserve} already does.
     */
    public List<UUID> reReserve(UUID jobId, int batchSize) {
        var rows = dsl.fetch("""
                UPDATE fact_contradictions
                SET status = 'in_flight', attempts = attempts + 1, job_id = ?
                WHERE status = 'retryable'
                  AND id IN (
                    SELECT id FROM fact_contradictions
                    WHERE status = 'retryable'
                    ORDER BY detected_at, id
                    LIMIT ?
                )
                RETURNING id
                """, jobId, batchSize);
        return toUuids(rows);
    }

    /**
     * Record a judge verdict: {@code contradiction = true} moves the pair to {@code pending} for
     * human review; {@code false} clears it as {@code not_contradictory}. Conditional on the row
     * still being {@code in_flight} so a duplicate webhook delivery (or a verdict arriving for a
     * pair the reconcile sweep already moved on) is a no-op rather than a silent overwrite.
     *
     * <p>{@code resolved_at} is set only on the {@code false} branch. {@code not_contradictory} is
     * terminal — it has no outbound transition in this class's lifecycle — so it gets a closure
     * timestamp like every other terminal write here ({@link #supersedeForPredicate}, {@link
     * #autoCloseInactive}); without one, a later "closed rows since X" query or a {@code
     * resolved_at}-ordered listing would silently miss it. {@code pending} is not terminal (it still
     * awaits {@code resolved}/{@code dismissed}), so {@code resolved_at} deliberately stays {@code
     * NULL} there — it is set once, by whichever write actually resolves the row.
     *
     * @return true iff this call made the write (false = duplicate delivery or unknown/moved-on pair)
     */
    public boolean recordVerdict(UUID pairId, boolean contradiction, double confidence, String rationale) {
        String status = contradiction ? "pending" : "not_contradictory";
        OffsetDateTime resolvedAt = contradiction ? null : OffsetDateTime.now();
        return dsl.execute("""
                UPDATE fact_contradictions
                SET status = ?, judge_confidence = ?, rationale = ?, resolved_at = ?::timestamptz
                WHERE id = ? AND status = 'in_flight'
                """, status, confidence, rationale, resolvedAt, pairId) == 1;
    }

    /**
     * Resolve a finished job's still-{@code in_flight} rows: those at or above {@code maxAttempts}
     * become {@code deferred}, the rest become {@code retryable} with {@code attempts} untouched. A
     * single atomic UPDATE deliberately, exactly as {@link
     * PredicateCardinalityRepository#applyAttemptRule} explains: two separate statements would leave
     * a window where a crash between them strands rows {@code in_flight} under an already-terminal
     * job, invisible to every later sweep.
     */
    public void applyAttemptRule(UUID jobId, int maxAttempts) {
        dsl.execute("""
                UPDATE fact_contradictions
                SET status = CASE WHEN attempts >= ? THEN 'deferred' ELSE 'retryable' END
                WHERE job_id = ? AND status = 'in_flight'
                """, maxAttempts, jobId);
    }

    /**
     * The compensation path for a job Vistierie declined to create (403 quota / 409 paused / 404
     * unregistered): the run never happened, so this tick's reservations must unwind as if it had
     * never run. A freshly reserved row ({@code attempts = 1}) is deleted outright — for it, "as if
     * the tick never ran" and "delete the row" are the same thing, since the row did not exist
     * before this tick. A re-reserved row ({@code attempts > 1}) instead reverts to {@code
     * retryable} with {@code attempts} decremented back to its pre-tick value: that row predates
     * this tick and must survive it, still carrying its true attempt count. A declined dispatch must
     * never walk a pair toward {@code deferred}.
     *
     * <p>{@code job_id} is cleared to {@code NULL} on the UPDATE leg, not left pointing at the
     * declined job: {@link ContradictionJobRepository#delete} is this path's very next call, and
     * {@code job_id} is a plain (non-cascading) foreign key to {@code contradiction_jobs.id} — a
     * re-reserved row left referencing the about-to-be-deleted job would make that DELETE fail with
     * a foreign-key violation. {@code NULL} is the correct value regardless: this leg reverts the
     * row to its pre-tick state, and a {@code retryable} row's {@code job_id} means only "the job
     * that last touched it," which this tick did not actually do.
     *
     * <p>{@code @Transactional}: the DELETE and UPDATE act on disjoint rows for two different
     * effects and cannot be collapsed into one statement, so atomicity is enforced by wrapping both
     * in one transaction — precedent: {@code SavedSearchRepository.save}
     * ({@code java-server/src/main/java/com/hivemem/savedsearch/SavedSearchRepository.java:27}).
     * Without it, a crash between the two would leave a re-reserved row {@code in_flight} with an
     * inflated attempt count, violating this method's own "as if the tick never ran" contract in
     * exactly the failure window it exists to close.
     */
    @Transactional
    public void compensate(UUID jobId) {
        dsl.execute("""
                DELETE FROM fact_contradictions
                WHERE job_id = ? AND status = 'in_flight' AND attempts = 1
                """, jobId);
        dsl.execute("""
                UPDATE fact_contradictions
                SET status = 'retryable', attempts = attempts - 1, job_id = NULL
                WHERE job_id = ? AND status = 'in_flight' AND attempts > 1
                """, jobId);
    }

    /** The {@code in_flight} pairs of a job, used by the webhook to know what was dispatched. */
    public List<UUID> inFlightIdsOfJob(UUID jobId) {
        var rows = dsl.fetch("""
                SELECT id FROM fact_contradictions WHERE job_id = ? AND status = 'in_flight' ORDER BY id
                """, jobId);
        return toUuids(rows);
    }

    /**
     * One {@code in_flight} row of a job, joined to both referenced facts' object values — exactly
     * the shape {@link ContradictionSweep} needs to build a {@link PairPayload} without re-deriving
     * this join itself.
     */
    public record PairForPayload(UUID id, String subject, String predicate, String objectA, String objectB) {}

    /** The {@code in_flight} pairs of a job with their subject/predicate/object values, for dispatch. */
    public List<PairForPayload> inFlightPayloadRowsOfJob(UUID jobId) {
        var rows = dsl.fetch("""
                SELECT fc.id, fc.subject, fc.predicate, fa."object" AS object_a, fb."object" AS object_b
                FROM fact_contradictions fc
                JOIN facts fa ON fa.id = fc.fact_a
                JOIN facts fb ON fb.id = fc.fact_b
                WHERE fc.job_id = ? AND fc.status = 'in_flight'
                ORDER BY fc.id
                """, jobId);
        List<PairForPayload> out = new ArrayList<>();
        for (Record r : rows) {
            out.add(new PairForPayload(
                    r.get("id", UUID.class),
                    r.get("subject", String.class),
                    r.get("predicate", String.class),
                    r.get("object_a", String.class),
                    r.get("object_b", String.class)));
        }
        return out;
    }

    /**
     * A predicate just flipped to {@code multi_valued}: close its still-open rows ({@code in_flight}
     * and {@code retryable}) as {@code superseded}. Rows in {@code pending}, {@code resolved},
     * {@code dismissed} or {@code deferred} are left untouched — they carry a human decision or
     * await one, and machine-rewriting them would destroy review history.
     *
     * @return the number of rows closed
     */
    public int supersedeForPredicate(String predicate) {
        return dsl.execute("""
                UPDATE fact_contradictions
                SET status = 'superseded', resolved_at = now()
                WHERE predicate = ? AND status IN ('in_flight', 'retryable')
                """, predicate);
    }

    /**
     * A referenced fact stopped being active (retracted, expired, or never committed) before a
     * human reviewed the pair: close it as {@code superseded}. Only {@code pending} rows qualify —
     * never {@code deferred}, which must stay inspectable for a human requeue decision, and never
     * {@code in_flight}, which {@link #recordVerdict} owns exclusively while a judge run is out.
     *
     * <p>{@code rationale} is deliberately left untouched: a {@code pending} row already carries the
     * judge's own explanation of why the pair was flagged (written by {@link #recordVerdict}), and
     * overwriting it would permanently destroy that review history — the exact thing this class's
     * Javadoc says machine cleanup must never do, and which {@link #supersedeForPredicate} already
     * gets right by touching only {@code status} and {@code resolved_at}.
     *
     * @return the number of rows closed
     */
    public int autoCloseInactive() {
        return dsl.execute("""
                UPDATE fact_contradictions fc
                SET status = 'superseded', resolved_at = now()
                WHERE fc.status = 'pending'
                  AND (NOT EXISTS (SELECT 1 FROM active_facts af WHERE af.id = fc.fact_a)
                       OR NOT EXISTS (SELECT 1 FROM active_facts af WHERE af.id = fc.fact_b))
                """);
    }

    private static List<UUID> toUuids(Iterable<Record> rows) {
        List<UUID> out = new ArrayList<>();
        for (Record r : rows) {
            out.add(r.get("id", UUID.class));
        }
        return out;
    }
}
