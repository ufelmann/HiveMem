package com.hivemem.contradiction;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * One side of a {@link ContradictionCandidate}: the fact ID, its object value, and the
 * timestamps/confidence used to decide whether this side is the more current fact.
 *
 * <p>{@code confidence} is non-null by construction because it is a primitive {@code double}. The
 * {@code facts} table column is {@code confidence REAL DEFAULT 1.0}, and a column default does not
 * prevent an explicit {@code NULL} row value — a loader mapping a jOOQ record into this type must
 * coalesce a {@code NULL} database value to {@code 1.0} itself, or it will hit an unboxing NPE on
 * the first such row instead of failing here.
 */
public record FactSide(UUID factId, String object, OffsetDateTime validFrom,
        OffsetDateTime ingestedAt, double confidence) {

    public FactSide {
        Objects.requireNonNull(factId, "FactSide.factId must not be null");
        Objects.requireNonNull(validFrom, "FactSide.validFrom must not be null");
        Objects.requireNonNull(ingestedAt, "FactSide.ingestedAt must not be null");
    }
}
