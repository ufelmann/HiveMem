package com.hivemem.contradiction;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

class ContradictionCandidateRepositoryIT extends ContradictionITSupport {

    @Autowired DSLContext dsl;
    @Autowired ContradictionCandidateRepository candidates;
    @Autowired PredicateCardinalityRepository predicateCardinality;

    @Test
    void findsOneCandidateForTwoOpenEndedFactsWithDifferentObjects() {
        predicateCardinality.setByHuman("lives_in", "single_valued", "one home at a time");
        UUID berlin = insertFact("alice", "lives_in", "Berlin");
        UUID munich = insertFact("alice", "lives_in", "Munich");

        List<ContradictionCandidate> result = candidates.findUnjudged(10, 10);

        assertThat(result).hasSize(1);
        ContradictionCandidate candidate = result.get(0);
        assertThat(candidate.subject()).isEqualTo("alice");
        assertThat(candidate.predicate()).isEqualTo("lives_in");
        assertThat(List.of(candidate.a().factId(), candidate.b().factId()))
                .containsExactlyInAnyOrder(berlin, munich);

        FactSide berlinSide = candidate.a().factId().equals(berlin) ? candidate.a() : candidate.b();
        FactSide munichSide = candidate.a().factId().equals(munich) ? candidate.a() : candidate.b();
        assertThat(berlinSide.object()).isEqualTo("Berlin");
        assertThat(munichSide.object()).isEqualTo("Munich");
        assertThat(berlinSide.confidence()).isEqualTo(1.0);
        assertThat(berlinSide.validFrom()).isNotNull();
        assertThat(berlinSide.ingestedAt()).isNotNull();
    }

    @Test
    void caseAndWhitespaceOnlyObjectDifferenceIsNotACandidate() {
        predicateCardinality.setByHuman("lives_in", "single_valued", "one home at a time");
        insertFact("alice", "lives_in", "Berlin");
        insertFact("alice", "lives_in", " berlin ");

        assertThat(candidates.findUnjudged(10, 10)).isEmpty();
    }

    @Test
    void predicateWithNoCardinalityRowProducesNoCandidate() {
        insertFact("alice", "lives_in", "Berlin");
        insertFact("alice", "lives_in", "Munich");

        assertThat(candidates.findUnjudged(10, 10)).isEmpty();
    }

    @Test
    void multiValuedPredicateProducesNoCandidate() {
        predicateCardinality.setByHuman("key_term", "multi_valued", "many per cell");
        insertFact("alice", "key_term", "hiking");
        insertFact("alice", "key_term", "cycling");

        assertThat(candidates.findUnjudged(10, 10)).isEmpty();
    }

    @Test
    void singleValuedButNotDecidedProducesNoCandidate() {
        dsl.execute("INSERT INTO predicate_cardinality (predicate, cardinality, status) "
                + "VALUES ('lives_in', 'single_valued', 'retryable')");
        insertFact("alice", "lives_in", "Berlin");
        insertFact("alice", "lives_in", "Munich");

        assertThat(candidates.findUnjudged(10, 10)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"in_flight", "retryable", "pending", "resolved", "dismissed",
            "superseded", "not_contradictory", "deferred"})
    void aPairAlreadyRecordedInAnyStatusNeverReentersThePool(String status) {
        predicateCardinality.setByHuman("lives_in", "single_valued", "test gate");
        UUID a = insertFact("alice", "lives_in", "Berlin");
        UUID b = insertFact("alice", "lives_in", "Munich");
        recordContradiction(a, b, "alice", "lives_in", status);

        assertThat(candidates.findUnjudged(100, 10)).as("status = %s", status).isEmpty();
    }

    @Test
    void recordedPairExclusionIsOrderIndependent() {
        predicateCardinality.setByHuman("lives_in", "single_valued", "test gate");
        UUID a = insertFact("alice", "lives_in", "Berlin");
        UUID b = insertFact("alice", "lives_in", "Munich");
        // Recorded as (b, a) - the reverse of how the query would generate it (a.id < b.id).
        recordContradiction(b, a, "alice", "lives_in", "pending");

        assertThat(candidates.findUnjudged(10, 10)).isEmpty();
    }

    /**
     * All five facts share one {@code ingested_at}, so {@code GREATEST(ingested_at)} alone cannot
     * order any of the 10 possible pairs relative to each other — the window's {@code a.id, b.id}
     * tie-break is the only thing deciding which 3 make the cap. Asserting the exact expected set
     * (not just its size, and not just "two calls agree with each other") is what makes this test
     * catch a regression that drops the tie-break: without it, Postgres is free to return any 3 of
     * the 10 pairs, and removing {@code a.id, b.id} from the window ORDER BY would not reliably fail
     * this test otherwise.
     *
     * <p>Comparison uses {@code UUID.toString()}, not {@code UUID.compareTo}: {@code compareTo} on
     * {@link UUID} compares the underlying {@code long}s as signed values, which disagrees with
     * Postgres's unsigned byte-wise UUID ordering for any UUID whose high bit differs between the
     * two. Canonical lowercase hex string comparison matches Postgres's byte order exactly.
     */
    @Test
    void groupCapReturnsExactlyTheSmallestIdPairsWhenAllTiesAreOnIngestedAt() {
        predicateCardinality.setByHuman("lives_in", "single_valued", "test gate");
        OffsetDateTime sharedIngestedAt = OffsetDateTime.now().minusDays(1);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(insertFact("alice", "lives_in", "City" + i,
                    sharedIngestedAt, null, sharedIngestedAt, 1.0));
        }
        List<UUID> sortedIds = ids.stream().sorted(Comparator.comparing(UUID::toString)).toList();
        // The window orders by (a.id, b.id) with a.id < b.id already enforced by the join, so the
        // 3 smallest pairs in that ordering are exactly the pairs formed by combining the smallest
        // ids first: (id0,id1), (id0,id2), (id0,id3).
        List<String> expectedPairs = List.of(
                sortedIds.get(0) + ":" + sortedIds.get(1),
                sortedIds.get(0) + ":" + sortedIds.get(2),
                sortedIds.get(0) + ":" + sortedIds.get(3));

        List<ContradictionCandidate> result = candidates.findUnjudged(100, 3);

        assertThat(toPairIds(result)).isEqualTo(expectedPairs);
    }

    /**
     * Guards {@code ORDER BY GREATEST(...)} specifically, not just "some" ordering: a pair's age is
     * the ingestion time of its <em>second</em> member, so a group whose second member arrived
     * recently is younger debt than a group whose second member arrived long ago, even if the first
     * group's first member is far older still. Alice's group has an old first-arrival (30 days ago)
     * but a young second-arrival (3 days ago) -> GREATEST = 3 days ago. Bob's group has both members
     * moderately old (10 and 9 days ago) -> GREATEST = 9 days ago, i.e. older debt than Alice's. So
     * with GREATEST ordering, Bob sorts first; with the wrong LEAST ordering, Alice would sort
     * first instead (30 days ago is older than Bob's LEAST of 10 days ago).
     */
    @Test
    void ordersOldestDebtFirstByTheSecondArrivingFactNotTheFirst() {
        predicateCardinality.setByHuman("lives_in", "single_valued", "test gate");
        OffsetDateTime now = OffsetDateTime.now();
        insertFact("alice", "lives_in", "Berlin", now.minusDays(30), null, now.minusDays(30), 1.0);
        insertFact("alice", "lives_in", "Munich", now.minusDays(3), null, now.minusDays(3), 1.0);
        insertFact("bob", "lives_in", "Hamburg", now.minusDays(10), null, now.minusDays(10), 1.0);
        insertFact("bob", "lives_in", "Cologne", now.minusDays(9), null, now.minusDays(9), 1.0);

        List<ContradictionCandidate> result = candidates.findUnjudged(1, 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).subject()).isEqualTo("bob");
    }

    /**
     * Guards the outer {@code ORDER BY}'s tie-break specifically, not the window's: four different
     * {@code (subject, predicate)} groups each contribute exactly one pair, all sharing one {@code
     * ingested_at}, so {@code GREATEST(...)} ties across every row the window ever emits — the only
     * thing left to make the final cross-group order deterministic is the {@code a_id, b_id}
     * tie-break on the outer {@code ORDER BY}. Without it, this is the case that can only be caught
     * by asserting exact sequence, not membership: {@link #groupCapReturnsExactlyTheSmallestIdPairsWhenAllTiesAreOnIngestedAt}
     * ties within a single window partition, which the outer clause does not additionally sort by
     * (a one-partition, size-capped result is already fully ordered by the window) — this test ties
     * across partitions, which only the outer clause can resolve.
     */
    @Test
    void ordersDeterministicallyAcrossGroupsWhenGreatestTimestampsAreTied() {
        predicateCardinality.setByHuman("lives_in", "single_valued", "test gate");
        OffsetDateTime sharedIngestedAt = OffsetDateTime.now().minusHours(1);
        record Pair(String subject, UUID a, UUID b) {}
        List<Pair> pairs = new ArrayList<>();
        for (String subject : List.of("alice", "bob", "carol", "dave")) {
            UUID x = insertFact(subject, "lives_in", "X", sharedIngestedAt, null, sharedIngestedAt, 1.0);
            UUID y = insertFact(subject, "lives_in", "Y", sharedIngestedAt, null, sharedIngestedAt, 1.0);
            boolean xIsA = x.toString().compareTo(y.toString()) < 0;
            pairs.add(new Pair(subject, xIsA ? x : y, xIsA ? y : x));
        }
        List<String> expectedSubjectOrder = pairs.stream()
                .sorted(Comparator.<Pair, String>comparing(p -> p.a().toString())
                        .thenComparing(p -> p.b().toString()))
                .map(Pair::subject)
                .toList();

        List<ContradictionCandidate> result = candidates.findUnjudged(4, 10);

        assertThat(result.stream().map(ContradictionCandidate::subject).toList())
                .containsExactlyElementsOf(expectedSubjectOrder);
    }

    @Test
    void limitIsHonouredAcrossGroups() {
        predicateCardinality.setByHuman("lives_in", "single_valued", "test gate");
        insertFact("alice", "lives_in", "Berlin");
        insertFact("alice", "lives_in", "Munich");
        insertFact("bob", "lives_in", "Hamburg");
        insertFact("bob", "lives_in", "Cologne");

        assertThat(candidates.findUnjudged(1, 10)).hasSize(1);
        assertThat(candidates.findUnjudged(2, 10)).hasSize(2);
    }

    @Test
    void pendingFactsAreOutOfThePoolUntilCommitted() {
        predicateCardinality.setByHuman("lives_in", "single_valued", "test gate");
        insertFact("alice", "lives_in", "Berlin");
        UUID pendingMunich = insertPendingFact("alice", "lives_in", "Munich");

        assertThat(candidates.findUnjudged(10, 10)).isEmpty();

        markFactCommitted(pendingMunich);

        assertThat(candidates.findUnjudged(10, 10)).hasSize(1);
    }

    @Test
    void nullConfidenceIsCoalescedToOneNotAnNpe() {
        predicateCardinality.setByHuman("lives_in", "single_valued", "test gate");
        OffsetDateTime now = OffsetDateTime.now();
        UUID a = insertFact("alice", "lives_in", "Berlin", now, null, now, null);
        UUID b = insertFact("alice", "lives_in", "Munich", now, null, now, 1.0);

        List<ContradictionCandidate> result = candidates.findUnjudged(10, 10);

        assertThat(result).hasSize(1);
        ContradictionCandidate candidate = result.get(0);
        FactSide berlinSide = candidate.a().factId().equals(a) ? candidate.a() : candidate.b();
        assertThat(berlinSide.confidence()).isEqualTo(1.0);
    }

    @Test
    void validTimeAdjacentBoundaryIsNotAnOverlap() {
        // Guards future semantics: no write path today produces a non-null valid_until, so this
        // matrix is set up with direct facts rather than any real write path.
        predicateCardinality.setByHuman("lives_in", "single_valued", "test gate");
        // boundary must stay in the future, or active_facts' own filter would exclude the Berlin
        // fact before the overlap predicate is ever evaluated.
        OffsetDateTime boundary = OffsetDateTime.now().plusDays(1);
        insertFact("alice", "lives_in", "Berlin", boundary.minusDays(2), boundary, boundary.minusDays(2), 1.0);
        insertFact("alice", "lives_in", "Munich", boundary, null, boundary, 1.0);

        assertThat(candidates.findUnjudged(10, 10)).isEmpty();
    }

    @Test
    void validTimeGenuineOverlapIsACandidate() {
        predicateCardinality.setByHuman("lives_in", "single_valued", "test gate");
        OffsetDateTime t0 = OffsetDateTime.now().minusDays(3);
        OffsetDateTime t1 = OffsetDateTime.now().minusDays(2);
        // a.valid_until must stay in the future, or active_facts' own filter (valid_until > now())
        // would exclude it before the overlap predicate is ever evaluated.
        OffsetDateTime t2 = OffsetDateTime.now().plusDays(1);
        insertFact("alice", "lives_in", "Berlin", t0, t2, t0, 1.0);
        insertFact("alice", "lives_in", "Munich", t1, null, t1, 1.0);

        assertThat(candidates.findUnjudged(10, 10)).hasSize(1);
    }

    @Test
    void validTimeBothOpenEndedIsACandidate() {
        predicateCardinality.setByHuman("lives_in", "single_valued", "test gate");
        insertFact("alice", "lives_in", "Berlin");
        insertFact("alice", "lives_in", "Munich");

        assertThat(candidates.findUnjudged(10, 10)).hasSize(1);
    }

    private static List<String> toPairIds(List<ContradictionCandidate> list) {
        return list.stream()
                .map(c -> c.a().factId() + ":" + c.b().factId())
                .sorted()
                .toList();
    }
}
