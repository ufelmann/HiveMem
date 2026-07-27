package com.hivemem.contradiction;

/**
 * One candidate pair: a shared subject and predicate, plus the two conflicting {@link FactSide}
 * values. ContradictionWinnerSelector is a pure function over both sides — it never reads the DB.
 */
public record ContradictionCandidate(String subject, String predicate, FactSide a, FactSide b) {}
