package com.hivemem.contradiction;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContradictionWinnerSelectorTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-01-01T00:00:00Z");
    private static final OffsetDateTime T2 = OffsetDateTime.parse("2026-06-01T00:00:00Z");

    @Test
    void newerValidFromWins() {
        assertThat(select(T1, T2, T1, T1, 1.0, 1.0)).isEqualTo(B);
        assertThat(select(T2, T1, T1, T1, 1.0, 1.0)).isEqualTo(A);
    }

    @Test
    void tieOnValidFromFallsBackToIngestedAt() {
        assertThat(select(T1, T1, T1, T2, 1.0, 1.0)).isEqualTo(B);
        assertThat(select(T1, T1, T2, T1, 1.0, 1.0)).isEqualTo(A);
    }

    @Test
    void tieOnBothTimestampsFallsBackToConfidence() {
        assertThat(select(T1, T1, T1, T1, 0.9, 0.4)).isEqualTo(A);
        assertThat(select(T1, T1, T1, T1, 0.4, 0.9)).isEqualTo(B);
    }

    @Test
    void completeTieYieldsNoRecommendation() {
        assertThat(select(T1, T1, T1, T1, 1.0, 1.0)).isNull();
    }

    /**
     * valid_from must outrank ingested_at: a fact learned later about an earlier period
     * must not win over one that is true now.
     */
    @Test
    void validFromOutranksIngestedAt() {
        assertThat(select(T2, T1, T1, T2, 1.0, 1.0)).isEqualTo(A);
    }

    /**
     * ingested_at must outrank confidence: a later ingestion must win even when its confidence
     * is lower, otherwise a misordered comparator tier would go unnoticed.
     */
    @Test
    void ingestedAtOutranksConfidence() {
        assertThat(select(T1, T1, T2, T1, 0.1, 0.9)).isEqualTo(A);
    }

    private UUID select(OffsetDateTime validFromA, OffsetDateTime validFromB,
                        OffsetDateTime ingestedA, OffsetDateTime ingestedB,
                        double confA, double confB) {
        FactSide a = new FactSide(A, "Berlin", validFromA, ingestedA, confA);
        FactSide b = new FactSide(B, "Munich", validFromB, ingestedB, confB);
        ContradictionCandidate c = new ContradictionCandidate("alice", "lives_in", a, b);
        return ContradictionWinnerSelector.suggestKeep(c);
    }
}
