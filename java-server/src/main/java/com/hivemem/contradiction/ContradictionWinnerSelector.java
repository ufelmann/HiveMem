package com.hivemem.contradiction;

import java.util.Comparator;
import java.util.UUID;

/**
 * Deterministically picks which of two contradicting facts is more likely current.
 *
 * <p><b>Why this is code, not a prompt.</b> HiveMem's contradiction pipeline asks an LLM judge
 * exactly one question: do the two objects of a same-subject, same-predicate fact pair really
 * denote different things (e.g. "Berlin" vs. "Munich"), or are they the same thing spelled
 * differently (e.g. "München" vs. "Munich")? The judge is never asked which fact is current, and
 * it never sees a timestamp. Freshness resolution is deliberately kept out of the LLM and done
 * here instead, in plain deterministic Java.
 *
 * <p>This split is backed by the MemoryAgentBench FactConsolidation results reported in
 * <i>Don't Ask the LLM to Track Freshness</i> (arXiv:2606.01435): systems that delegate the
 * "which fact wins" decision to a model score far below a naive long-context baseline
 * (Zep/Graphiti 7%, Mem0 18%, MemGPT 28%, vs. 60% for plain long-context). Extracting candidates
 * with an LLM but deciding the winner with deterministic code instead reaches 94.8%. Folding this
 * logic back into the judge prompt to "simplify" the pipeline would reintroduce exactly the
 * failure mode the split exists to avoid — do not do that.
 *
 * <p>Precedence, applied per {@link ContradictionCandidate}:
 *
 * <ol>
 *   <li>newest {@code valid_from} wins
 *   <li>tie &rarr; newest {@code ingested_at} wins
 *   <li>tie &rarr; higher confidence wins
 *   <li>tie &rarr; no recommendation ({@code null}); a human decides
 * </ol>
 */
public final class ContradictionWinnerSelector {

    // Tier order below is the single source of truth for the precedence documented in the class
    // Javadoc: valid_from, then ingested_at, then confidence. Do not reorder these thenComparing
    // calls without also updating the Javadoc and re-checking ingestedAtOutranksConfidence.
    private static final Comparator<FactSide> PRECEDENCE =
            Comparator.comparing(FactSide::validFrom)
                    .thenComparing(FactSide::ingestedAt)
                    .thenComparingDouble(FactSide::confidence);

    private ContradictionWinnerSelector() {}

    /**
     * Returns the fact ID of the candidate more likely to be current, or {@code null} if the two
     * facts are indistinguishable under the precedence rules above.
     */
    public static UUID suggestKeep(ContradictionCandidate c) {
        int comparison = PRECEDENCE.compare(c.a(), c.b());
        if (comparison > 0) {
            return c.a().factId();
        }
        if (comparison < 0) {
            return c.b().factId();
        }
        return null;
    }
}
