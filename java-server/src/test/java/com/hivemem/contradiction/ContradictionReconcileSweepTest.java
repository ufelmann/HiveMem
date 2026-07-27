package com.hivemem.contradiction;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A plain-Mockito unit test, deliberately without Spring/Testcontainers: pins the exact arguments
 * {@link ContradictionReconcileSweep#reconcile} passes to {@link
 * ContradictionJobRepository#findStale}, which {@code ContradictionReconcileSweepIT} cannot pin on
 * its own. {@link ContradictionJobRepository#reclaimStale} independently re-checks staleness against
 * the same {@code staleThreshold} at write time, so a mutant that drops (or shrinks) the {@code
 * findStale} filter is otherwise silently absorbed by that second check in every IT scenario - the
 * fresh job would simply fail {@code reclaimStale} instead of being skipped by {@code findStale}, and
 * every IT assertion still passes. Only pinning the actual argument catches that mutant.
 */
class ContradictionReconcileSweepTest {

    @Test
    void reconcilePassesTheConfiguredStaleThresholdAndABoundedBatchSizeToFindStale() {
        ContradictionProperties props = mock(ContradictionProperties.class);
        ContradictionJobRepository jobs = mock(ContradictionJobRepository.class);
        ContradictionRepository pairs = mock(ContradictionRepository.class);
        PredicateCardinalityRepository cardinality = mock(PredicateCardinalityRepository.class);

        Duration configuredThreshold = Duration.ofMinutes(7);
        when(props.getStaleThreshold()).thenReturn(configuredThreshold);
        when(jobs.findStale(any(), anyInt())).thenReturn(List.of());

        ContradictionReconcileSweep sweep = new ContradictionReconcileSweep(props, jobs, pairs, cardinality);
        sweep.reconcile();

        verify(jobs).findStale(eq(configuredThreshold), eq(10));
    }

    /**
     * The IT suite cannot pin this: {@code autoCloseInactive} is idempotent, so "called once, after
     * the per-job loop" and "called once per stale job, inside the loop" leave an identical DB state
     * behind after a single {@code reconcile()} call. Two stale jobs of different kinds here means a
     * "moved inside the loop" mutant would call it twice; only counting the mock invocation catches
     * that.
     */
    @Test
    void autoCloseInactiveIsCalledExactlyOncePerTickRegardlessOfHowManyStaleJobsWereFound() {
        ContradictionProperties props = mock(ContradictionProperties.class);
        ContradictionJobRepository jobs = mock(ContradictionJobRepository.class);
        ContradictionRepository pairs = mock(ContradictionRepository.class);
        PredicateCardinalityRepository cardinality = mock(PredicateCardinalityRepository.class);

        when(props.getStaleThreshold()).thenReturn(Duration.ofMinutes(10));
        when(props.getMaxAttempts()).thenReturn(3);
        ContradictionJobRepository.Job pairsJob = new ContradictionJobRepository.Job(
                UUID.randomUUID(), UUID.randomUUID(), "run-1", "pairs", 1, "awaiting");
        ContradictionJobRepository.Job cardinalityJob = new ContradictionJobRepository.Job(
                UUID.randomUUID(), UUID.randomUUID(), "run-2", "cardinality", 1, "processing");
        when(jobs.findStale(any(), anyInt())).thenReturn(List.of(pairsJob, cardinalityJob));
        when(jobs.reclaimStale(any(), any())).thenReturn(true);
        when(jobs.markFailed(any())).thenReturn(true);
        when(pairs.autoCloseInactive()).thenReturn(0);

        ContradictionReconcileSweep sweep = new ContradictionReconcileSweep(props, jobs, pairs, cardinality);
        sweep.reconcile();

        verify(pairs, times(1)).autoCloseInactive();
    }
}
