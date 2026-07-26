package com.hivemem.contradiction;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/**
 * Stage 1 of contradiction detection: the pure-SQL candidate query. No LLM, no dispatch — this
 * only finds pairs of active facts that are worth asking a judge about. The sweep that consumes
 * {@link #findUnjudged} and turns candidates into {@code fact_contradictions} rows is a later task.
 *
 * <p>A candidate is a self-join of {@code active_facts} on {@code (subject, predicate)} where the
 * two objects disagree (case/whitespace-insensitively), gated by {@link
 * PredicateCardinalityRepository}'s Stage-A verdict: only {@code single_valued} predicates produce
 * candidates, exactly as {@code PredicateCardinalityRepository}'s class Javadoc explains — a
 * multi-valued predicate disagreeing on objects is expected, not a contradiction.
 *
 * <p>{@code a.id < b.id} does double duty: it turns the self-join into unordered pairs (each pair
 * appears once, not twice), and combined with the {@code CHECK (fact_a <> fact_b)} constraint on
 * {@code fact_contradictions} it guarantees no self-pair ever needs excluding.
 *
 * <p>The {@code NOT EXISTS} against {@code fact_contradictions} is matched order-independently via
 * {@code LEAST}/{@code GREATEST}, mirroring {@code ux_fact_contradictions_pair}'s own definition —
 * a pair recorded as {@code (b, a)} must still exclude the candidate seen here as {@code (a, b)}.
 *
 * <p>Each {@code (subject, predicate)} group is capped at {@code maxPairsPerGroup} candidates via
 * a {@code row_number()} window, deliberately ordered by {@code GREATEST(a.ingested_at,
 * b.ingested_at)} then {@code a.id, b.id} — without a pinned tie-break, which pairs enter a batch
 * would be nondeterministic (Postgres does not guarantee window-function row order otherwise) and
 * therefore untestable. The outer {@code ORDER BY} + {@code LIMIT} decides which pairs enter a
 * dispatch batch exactly as much as the window does, so it carries the identical {@code a_id,
 * b_id} tie-break — {@code facts.ingested_at} defaults to the transaction timestamp {@code now()},
 * so every fact written by one batched extraction shares it, and ties straddling the {@code LIMIT}
 * boundary are the normal case, not a corner one. The {@code GREATEST} expression itself is used
 * because a pair only becomes detectable the moment its <em>second</em> member is ingested, so
 * that is the pair's age, not the older side's {@code ingested_at} (which is {@code LEAST}, and
 * would make an old-vs-brand-new pair look ancient).
 *
 * <p>{@code confidence} is coalesced to {@code 1.0} in SQL ({@code COALESCE(a.confidence, 1.0)}),
 * not in {@link #side}: {@code facts.confidence} is {@code REAL DEFAULT 1.0}, and a column default
 * does not prevent an explicit {@code NULL} row value (see {@link FactSide}'s Javadoc). Coalescing
 * in SQL means {@link #side} can read a primitive {@code double} directly — the null-handling
 * travels with the query text itself, so a future caller copying this SQL inherits the same
 * guarantee instead of having to remember a separate Java-side fix-up.
 *
 * <p>The valid-time overlap predicate — {@code (a.valid_until IS NULL OR a.valid_until >
 * b.valid_from) AND (b.valid_until IS NULL OR b.valid_until > a.valid_from)} — is currently inert.
 * No write path sets a semantic {@code valid_until}: {@code WriteToolRepository.kgAdd} takes only
 * {@code validFrom}, and the only writers of {@code valid_until} ({@code invalidateFact}, {@code
 * reviseFact}) set it to {@code now()} — a row like that is immediately excluded by {@code
 * active_facts}' own {@code valid_until > now()} filter. So every row this query can see today has
 * {@code valid_until IS NULL} on both sides, and the predicate is always true. It is kept anyway
 * because it is correct, costs nothing, and is the one line that must already be right on the day
 * a semantic {@code valid_until} write path appears — do not delete it as dead code, and do not
 * read its presence as closing any gap in today's behaviour, because it does not.
 */
@Repository
public class ContradictionCandidateRepository {

    private final DSLContext dsl;

    public ContradictionCandidateRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<ContradictionCandidate> findUnjudged(int limit, int maxPairsPerGroup) {
        var rows = dsl.fetch("""
                SELECT subject, predicate,
                       a_id, a_object, a_valid_from, a_ingested_at, a_confidence,
                       b_id, b_object, b_valid_from, b_ingested_at, b_confidence
                FROM (
                    SELECT
                        a.subject AS subject,
                        a.predicate AS predicate,
                        a.id AS a_id, a."object" AS a_object, a.valid_from AS a_valid_from,
                        a.ingested_at AS a_ingested_at, COALESCE(a.confidence, 1.0) AS a_confidence,
                        b.id AS b_id, b."object" AS b_object, b.valid_from AS b_valid_from,
                        b.ingested_at AS b_ingested_at, COALESCE(b.confidence, 1.0) AS b_confidence,
                        row_number() OVER (
                            PARTITION BY a.subject, a.predicate
                            ORDER BY GREATEST(a.ingested_at, b.ingested_at), a.id, b.id
                        ) AS rn
                    FROM active_facts a
                    JOIN active_facts b ON a.subject = b.subject AND a.predicate = b.predicate AND a.id < b.id
                    WHERE lower(btrim(a."object")) <> lower(btrim(b."object"))
                      AND (a.valid_until IS NULL OR a.valid_until > b.valid_from)
                      AND (b.valid_until IS NULL OR b.valid_until > a.valid_from)
                      AND a.predicate IN (
                          SELECT predicate FROM predicate_cardinality
                          WHERE status = 'decided' AND cardinality = 'single_valued'
                      )
                      AND NOT EXISTS (
                          SELECT 1 FROM fact_contradictions fc
                          WHERE LEAST(fc.fact_a, fc.fact_b) = LEAST(a.id, b.id)
                            AND GREATEST(fc.fact_a, fc.fact_b) = GREATEST(a.id, b.id)
                      )
                ) candidates
                WHERE rn <= ?
                ORDER BY GREATEST(a_ingested_at, b_ingested_at), a_id, b_id
                LIMIT ?
                """, maxPairsPerGroup, limit);

        List<ContradictionCandidate> out = new ArrayList<>();
        for (Record r : rows) {
            out.add(new ContradictionCandidate(
                    r.get("subject", String.class),
                    r.get("predicate", String.class),
                    side(r, "a"),
                    side(r, "b")));
        }
        return out;
    }

    private static FactSide side(Record r, String prefix) {
        return new FactSide(
                r.get(prefix + "_id", UUID.class),
                r.get(prefix + "_object", String.class),
                r.get(prefix + "_valid_from", OffsetDateTime.class),
                r.get(prefix + "_ingested_at", OffsetDateTime.class),
                r.get(prefix + "_confidence", double.class));
    }
}
