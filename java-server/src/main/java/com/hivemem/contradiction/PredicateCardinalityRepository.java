package com.hivemem.contradiction;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence for {@code predicate_cardinality} — Stage A of contradiction detection.
 *
 * <p>Two active facts sharing a subject and predicate but disagreeing on the object are only a
 * candidate contradiction if the predicate is single-valued; a cell can legitimately have many
 * {@code key_term}s, and a person can {@code like} many things. Measured against the real
 * production graph: 2 695 active facts produce 3 764 candidate pairs, and 3 750 of them (99.6 %)
 * share one predicate — {@code key_term}, an obviously multi-valued one. Judging per pair would
 * spend the entire daily LLM dispatch quota re-deriving the same answer thousands of times over.
 *
 * <p>So the question is asked once per <em>predicate</em>, never per pair: is this predicate
 * single-valued? The verdict is cached here and gates the whole pair pipeline (Stage B, built in
 * a later task) — roughly a dozen verdicts replace thousands of pair judgements. Do not
 * "improve" this into a per-pair question; that is the exact design mistake this table exists to
 * avoid.
 *
 * <p>{@code cardinality} is nullable on purpose: a row is written the moment a predicate is
 * <em>reserved for asking</em> ({@link #reserve}), before any verdict exists. That reservation row
 * is what stops an unanswered predicate from being re-discovered by {@link #findUnjudged} and
 * re-dispatched on every tick.
 *
 * <p>Lifecycle mirrors {@link ContradictionJobRepository}'s job rows: {@code in_flight} →
 * {@code decided} on a verdict ({@link #recordVerdict}), or → {@code retryable} when its job fails
 * ({@link #applyAttemptRule}), or → {@code deferred} once the attempt ceiling is hit. {@code
 * attempts} is incremented at re-dispatch ({@link #reReserve}), not at failure, so the ceiling is
 * reached after exactly {@code maxAttempts} dispatches, not {@code maxAttempts} failures plus one.
 *
 * <p>A human verdict ({@link #setByHuman}) outranks every judge verdict, now and later:
 * {@link #recordVerdict} refuses to overwrite a row whose {@code decided_by} is already {@code
 * 'human'}.
 */
@Repository
public class PredicateCardinalityRepository {

    private final DSLContext dsl;

    public PredicateCardinalityRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Predicates that have at least one {@code (subject, predicate)} group with more than one
     * distinct object (case-insensitive, trimmed) in {@code active_facts}, and no row at all yet
     * in {@code predicate_cardinality} (any status).
     *
     * <p>The inner query groups by {@code (subject, predicate)} to find multi-object groups; the
     * outer {@code GROUP BY predicate} collapses that back down to distinct predicates. The result
     * cannot contain duplicates: the outer grouping is exactly what enforces distinctness, the same
     * way {@code SELECT DISTINCT} would.
     */
    public List<String> findUnjudged(int limit) {
        var rows = dsl.fetch("""
                SELECT predicate FROM (
                    SELECT predicate
                    FROM active_facts
                    GROUP BY subject, predicate
                    HAVING count(DISTINCT lower(btrim("object"))) > 1
                ) multi_valued_groups
                WHERE NOT EXISTS (
                    SELECT 1 FROM predicate_cardinality pc
                    WHERE pc.predicate = multi_valued_groups.predicate
                )
                GROUP BY predicate
                ORDER BY predicate
                LIMIT ?
                """, limit);
        return toStrings(rows, "predicate");
    }

    /**
     * Reserve predicates for a newly dispatched job: insert {@code in_flight} rows with {@code
     * attempts = 1}. Idempotent against a concurrent reservation of the same predicate via {@code
     * ON CONFLICT DO NOTHING} — the first writer wins, the loser's row is simply not inserted.
     */
    public void reserve(List<String> predicates, UUID jobId) {
        if (predicates.isEmpty()) return;
        dsl.execute("""
                INSERT INTO predicate_cardinality (predicate, status, attempts, job_id)
                SELECT unnest(?::text[]), 'in_flight', 1, ?
                ON CONFLICT (predicate) DO NOTHING
                """, predicates.toArray(String[]::new), jobId);
    }

    /**
     * Re-reserve predicates that failed a previous job: bump {@code attempts} and move back to
     * {@code in_flight} under the new job id. Only touches rows still {@code retryable} — a row
     * moved on (e.g. to {@code deferred} by a concurrent sweep) is left alone.
     */
    public void reReserve(List<String> predicates, UUID jobId) {
        if (predicates.isEmpty()) return;
        dsl.execute("""
                UPDATE predicate_cardinality
                SET status = 'in_flight', attempts = attempts + 1, job_id = ?
                WHERE predicate = ANY(?) AND status = 'retryable'
                """, jobId, predicates.toArray(String[]::new));
    }

    public List<String> findRetryable(int limit) {
        var rows = dsl.fetch("""
                SELECT predicate FROM predicate_cardinality
                WHERE status = 'retryable'
                ORDER BY predicate LIMIT ?
                """, limit);
        return toStrings(rows, "predicate");
    }

    /**
     * Record a judge verdict. Refuses to overwrite a row already settled by a human — {@link
     * #setByHuman} outranks the judge permanently, so this UPDATE is conditional on {@code
     * decided_by} not already being {@code 'human'}. The {@code IS DISTINCT FROM} form is
     * deliberate: {@code decided_by <> 'human'} is {@code NULL} (i.e. false) for the common case
     * of an unjudged row whose {@code decided_by} is still {@code NULL}, which would silently
     * block every first verdict.
     *
     * @return true iff this caller wrote the verdict (false if blocked by an existing human
     *     verdict, or if the predicate has no row at all)
     */
    public boolean recordVerdict(String predicate, String cardinality, double confidence, String rationale) {
        return dsl.execute("""
                UPDATE predicate_cardinality
                SET cardinality = ?, status = 'decided', confidence = ?, rationale = ?,
                    decided_by = 'judge', decided_at = now()
                WHERE predicate = ? AND decided_by IS DISTINCT FROM 'human'
                """, cardinality, confidence, rationale, predicate) == 1;
    }

    /**
     * Human override, upserted so it works whether or not the predicate has been reserved yet.
     * Outranks every judge verdict, now and later — see {@link #recordVerdict}. Clears {@code
     * confidence} and {@code job_id} on conflict so a stale judge confidence score (or a dead
     * job reference) never rides along with a human verdict that supersedes it.
     */
    public void setByHuman(String predicate, String cardinality, String reason) {
        dsl.execute("""
                INSERT INTO predicate_cardinality (predicate, cardinality, status, decided_by, decided_at, rationale)
                VALUES (?, ?, 'decided', 'human', now(), ?)
                ON CONFLICT (predicate) DO UPDATE
                SET cardinality = excluded.cardinality, status = 'decided',
                    decided_by = 'human', decided_at = now(), rationale = excluded.rationale,
                    confidence = NULL, job_id = NULL
                """, predicate, cardinality, reason);
    }

    /**
     * Resolve a finished job's still-{@code in_flight} rows: those at or above {@code maxAttempts}
     * become {@code deferred}, the rest become {@code retryable} with {@code attempts} untouched.
     * A single atomic UPDATE deliberately, not two: two separate statements would leave a window
     * where a crash between them strands rows {@code in_flight} under an already-terminal job —
     * invisible to {@link #findUnjudged} (a row exists) and invisible to {@link #findRetryable}
     * (not yet {@code retryable}), with nothing in this design ever revisiting them again.
     */
    public void applyAttemptRule(UUID jobId, int maxAttempts) {
        dsl.execute("""
                UPDATE predicate_cardinality
                SET status = CASE WHEN attempts >= ? THEN 'deferred' ELSE 'retryable' END
                WHERE job_id = ? AND status = 'in_flight'
                """, maxAttempts, jobId);
    }

    /**
     * The compensation path for a job Vistierie declined to create (403 quota / 409 paused / 404
     * unregistered): the run never happened, so this tick's reservations must unwind as if it had
     * never run. A freshly reserved row ({@code attempts = 1}) is deleted outright; a re-reserved
     * row ({@code attempts > 1}) reverts to {@code retryable} with {@code attempts} decremented
     * back to its pre-tick value. A stop signal must never walk a predicate toward {@code
     * deferred}.
     *
     * <p>{@code @Transactional}: the DELETE and UPDATE cannot be collapsed into one statement (they
     * act on disjoint rows for two different effects), so atomicity is enforced by wrapping both in
     * one transaction — precedent: {@code SavedSearchRepository.save}. Without it, a crash between
     * the two would leave a re-reserved row {@code in_flight} with an inflated attempt count,
     * violating this method's own "as if the tick never ran" contract in exactly the failure
     * window it exists to close.
     */
    @Transactional
    public void compensate(UUID jobId) {
        dsl.execute("""
                DELETE FROM predicate_cardinality
                WHERE job_id = ? AND status = 'in_flight' AND attempts = 1
                """, jobId);
        dsl.execute("""
                UPDATE predicate_cardinality
                SET status = 'retryable', attempts = attempts - 1
                WHERE job_id = ? AND status = 'in_flight' AND attempts > 1
                """, jobId);
    }

    /** The {@code in_flight} predicates of a job, used by the webhook to know what was dispatched. */
    public List<String> predicatesOfJob(UUID jobId) {
        var rows = dsl.fetch("""
                SELECT predicate FROM predicate_cardinality
                WHERE job_id = ? AND status = 'in_flight'
                ORDER BY predicate
                """, jobId);
        return toStrings(rows, "predicate");
    }

    /** The Stage-B gate: predicates confirmed single-valued, and only those. */
    public List<String> singleValuedPredicates() {
        var rows = dsl.fetch("""
                SELECT predicate FROM predicate_cardinality
                WHERE status = 'decided' AND cardinality = 'single_valued'
                ORDER BY predicate
                """);
        return toStrings(rows, "predicate");
    }

    private static List<String> toStrings(Iterable<Record> rows, String column) {
        List<String> out = new ArrayList<>();
        for (Record r : rows) out.add(r.get(column, String.class));
        return out;
    }
}
