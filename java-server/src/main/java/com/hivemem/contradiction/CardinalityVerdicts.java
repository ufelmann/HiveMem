package com.hivemem.contradiction;

import java.util.List;

/**
 * The cardinality-judge completion callback envelope: {@code {run_id, status,
 * output:{verdicts:[...]}}}. Same shape as {@link PairVerdicts}, against predicates instead of
 * fact pairs — see that class's Javadoc for why {@code output} is nullable.
 */
public record CardinalityVerdicts(String run_id, String status, Output output) {

    public record Output(List<Verdict> verdicts) {}

    /**
     * One judge verdict on a predicate's cardinality: {@code single_valued} or {@code
     * multi_valued} — see {@link PredicateCardinalityRepository}'s Javadoc for what each means.
     */
    public record Verdict(String predicate, String cardinality, double confidence, String rationale) {}
}
