package com.hivemem.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfidenceLevelTest {

    private static final ConfidenceThresholds T = new ConfidenceThresholds(0.20);

    // Spread set: mean 0.40, population sigma ~= 0.0816496581
    private static final ResultSetStats SPREAD = ResultSetStats.of(List.of(0.30, 0.40, 0.50));

    @Test
    void belowFloorIsNone() {
        assertThat(ConfidenceLevel.classify(0.10, SPREAD, T)).isEqualTo(ConfidenceLevel.NONE);
    }

    @Test
    void singleElementSetIsNoneEvenAboveFloor() {
        ResultSetStats single = ResultSetStats.of(List.of(0.5));
        assertThat(ConfidenceLevel.classify(0.5, single, T)).isEqualTo(ConfidenceLevel.NONE);
    }

    @Test
    void emptySetIsNone() {
        ResultSetStats empty = ResultSetStats.of(List.of());
        assertThat(ConfidenceLevel.classify(0.5, empty, T)).isEqualTo(ConfidenceLevel.NONE);
    }

    @Test
    void spreadSetClassifiesRelativeToDistribution() {
        // mean 0.40, sigma ~= 0.081649 -> mean + sigma ~= 0.481649
        assertThat(ConfidenceLevel.classify(0.50, SPREAD, T)).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(ConfidenceLevel.classify(0.40, SPREAD, T)).isEqualTo(ConfidenceLevel.MEDIUM);
        assertThat(ConfidenceLevel.classify(0.30, SPREAD, T)).isEqualTo(ConfidenceLevel.LOW);
    }

    @Test
    void zeroSigmaSetIsMedium() {
        ResultSetStats flat = ResultSetStats.of(List.of(0.40, 0.40));
        assertThat(ConfidenceLevel.classify(0.40, flat, T)).isEqualTo(ConfidenceLevel.MEDIUM);
    }

    @Test
    void floorGuardBeatsDistribution() {
        // Below floor wins over any distribution-relative classification.
        assertThat(ConfidenceLevel.classify(0.05, SPREAD, T)).isEqualTo(ConfidenceLevel.NONE);
    }

    @Test
    void poorlyMatchedCellLosesHighWhenPopularityIsNoLongerADominantOutlier() {
        // Popularity-normalization regression guard (spec §3.1's sixth case).
        // ConfidenceLevel.classify is relative to the result-set distribution and
        // therefore invariant to any affine (scale+shift) transform applied
        // uniformly to every score -- so "scores shrink a bit" proves nothing on
        // its own. What must be shown is that the SAME cell set, ranked once
        // under the old popularity formula and once under the new one, produces
        // a DIFFERENT HIGH membership: a cell whose only strong signal is a
        // popularity outlier must fall out of HIGH once that outlier is damped.
        //
        // background: eight cells with real content-driven scores (kw > 0), a
        // spread mirroring genuine relevance variation -- unaffected by this
        // change either way.
        List<Double> background = List.of(0.32, 0.34, 0.36, 0.38, 0.40, 0.42, 0.44, 0.46);

        // popularCell: weak content match (kw = 0, like dc353f36 in the spec's
        // real example) that only ranks via score_popularity. Base content-only
        // contribution is 0.30; the popularity contribution is added on top.
        double popularCellBase = 0.30;
        double oldPopularityBonus = 0.15;              // old: 7 hits / observed max 7 -> 1.0 * weight 0.15
        double newPopularityBonus = (7.0 / 25.0) * 0.15; // new: 7 hits / fixed reference 25 -> 0.28 * weight 0.15

        double popularCellOld = popularCellBase + oldPopularityBonus;
        double popularCellNew = popularCellBase + newPopularityBonus;

        List<Double> oldScores = new java.util.ArrayList<>(background);
        oldScores.add(popularCellOld);
        List<Double> newScores = new java.util.ArrayList<>(background);
        newScores.add(popularCellNew);

        ResultSetStats oldStats = ResultSetStats.of(oldScores);
        ResultSetStats newStats = ResultSetStats.of(newScores);

        ConfidenceLevel popularCellOldLevel = ConfidenceLevel.classify(popularCellOld, oldStats, T);
        ConfidenceLevel popularCellNewLevel = ConfidenceLevel.classify(popularCellNew, newStats, T);

        assertThat(popularCellOldLevel)
                .as("under the old MAX(recent_access_count) normalization, the popularity "
                        + "outlier clears mean+sigma and is classified HIGH")
                .isEqualTo(ConfidenceLevel.HIGH);
        assertThat(popularCellNewLevel)
                .as("under the fixed reference of 25, the same cell no longer clears mean+sigma")
                .isNotEqualTo(ConfidenceLevel.HIGH);

        // The HIGH set as a whole differs between the two rankings of the same
        // cell set -- not just this one cell's label in isolation.
        java.util.Set<Double> highOld = highSet(oldScores, oldStats);
        java.util.Set<Double> highNew = highSet(newScores, newStats);
        assertThat(highOld).contains(popularCellOld);
        assertThat(highNew).doesNotContain(popularCellNew);
        assertThat(highOld).isNotEqualTo(highNew);
    }

    private static java.util.Set<Double> highSet(List<Double> scores, ResultSetStats stats) {
        java.util.Set<Double> result = new java.util.LinkedHashSet<>();
        for (double score : scores) {
            if (ConfidenceLevel.classify(score, stats, T) == ConfidenceLevel.HIGH) {
                result.add(score);
            }
        }
        return result;
    }
}
