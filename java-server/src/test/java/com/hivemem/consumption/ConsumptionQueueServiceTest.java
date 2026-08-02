package com.hivemem.consumption;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConsumptionQueueServiceTest {

    /** The floor of 2 is deliberate: at the measured rate of 1 degraded page in 93, flagging every
     *  single one would put most of a 39 000-page run's ~390 batches into the queue, and a queue
     *  that is always full is a queue nobody reads. */
    private static ConsumptionProperties props() {
        ConsumptionProperties p = new ConsumptionProperties();
        p.setRecoveryStaleThreshold(java.time.Duration.ofMinutes(30));
        return p;
    }

    @Test
    void aSingleDegradedPageDoesNotReachTheQueue() {
        ConsumptionFileRepository repo = mock(ConsumptionFileRepository.class);
        when(repo.findDegradedBatches(anyInt(), anyInt())).thenReturn(List.of());
        when(repo.countsByState()).thenReturn(Map.of("done", 20));
        ConsumptionRecoverySweep sweep = mock(ConsumptionRecoverySweep.class);
        when(sweep.lastReconciliation())
                .thenReturn(new ConsumptionRecoverySweep.Reconciliation(0, 0, 0));

        var queue = new ConsumptionQueueService(repo, sweep, props()).queue(50);

        assertTrue(queue.degradedBatches().isEmpty());
        verify(repo).findDegradedBatches(2, 50);   // minimum 2 degraded pages
    }

    /** M4: the queue must read the newest-first failed list, not {@code findRetriableFailed}'s
     *  oldest-first, budget-filtered one — with more failures than the limit, oldest-first shows an
     *  operator the same 50 ancient rows forever. The sweep's own ordering stays untouched. */
    @Test
    void queueReadsTheNewestFirstFailedListAndNotTheRetrySweepsOrdering() {
        ConsumptionFileRepository repo = mock(ConsumptionFileRepository.class);
        when(repo.findFailedNewestFirst(anyInt())).thenReturn(List.of(
                new ConsumptionFileRepository.Row("sha-new", "newest.pdf", "failed", 1, "boom")));
        when(repo.findDegradedBatches(anyInt(), anyInt())).thenReturn(List.of());
        when(repo.countsByState()).thenReturn(Map.of());
        ConsumptionRecoverySweep sweep = mock(ConsumptionRecoverySweep.class);
        when(sweep.lastReconciliation())
                .thenReturn(new ConsumptionRecoverySweep.Reconciliation(0, 0, 0));

        var queue = new ConsumptionQueueService(repo, sweep, props()).queue(50);

        assertEquals("newest.pdf", queue.failedFiles().get(0).filename());
        verify(repo).findFailedNewestFirst(50);
        verify(repo, never()).findRetriableFailed(anyInt(), anyInt());
    }

    /** I4: a batch that stalled rather than failed gets its own reviewable section, carrying enough
     *  identity (sha256, filename, state, age) for an operator to act on — previously it existed
     *  only as an anonymous integer in stateCounts. The cut-off is the recovery stale threshold. */
    @Test
    void stalledRowsAreListedUsingTheRecoveryStaleThreshold() {
        ConsumptionFileRepository repo = mock(ConsumptionFileRepository.class);
        when(repo.findDegradedBatches(anyInt(), anyInt())).thenReturn(List.of());
        when(repo.findFailedNewestFirst(anyInt())).thenReturn(List.of());
        when(repo.countsByState()).thenReturn(Map.of("processing", 1));
        when(repo.findStalledRows(anyInt(), anyInt())).thenReturn(List.of(
                new ConsumptionFileRepository.StalledRow(
                        "sha-stall", "stuck.pdf", "processing", "2026-08-02T10:00:00Z", 4200L)));
        ConsumptionRecoverySweep sweep = mock(ConsumptionRecoverySweep.class);
        when(sweep.lastReconciliation())
                .thenReturn(new ConsumptionRecoverySweep.Reconciliation(0, 0, 0));

        var queue = new ConsumptionQueueService(repo, sweep, props()).queue(50);

        verify(repo).findStalledRows(1800, 50);   // 30m threshold, in seconds
        assertEquals(1, queue.stalledRows().size());
        assertEquals("stuck.pdf", queue.stalledRows().get(0).filename());
        assertEquals("processing", queue.stalledRows().get(0).state());
        assertEquals(4200L, queue.stalledRows().get(0).ageSeconds());
    }

    /**
     * Reconciliation's component order is (orphansRestaged, rowsWithoutFile, misplacedFailed) — see
     * ConsumptionRecoverySweep.Reconciliation. The brief this test was written from predates that
     * record's current shape (it no longer has a doneLeftovers field, and gained misplacedFailed);
     * the assertions below are built from the actual record, not copied from the stale brief.
     */
    @Test
    void reconciliationCountersAreExposed() {
        ConsumptionFileRepository repo = mock(ConsumptionFileRepository.class);
        when(repo.findDegradedBatches(anyInt(), anyInt())).thenReturn(List.of());
        when(repo.countsByState()).thenReturn(Map.of("failed", 3));
        ConsumptionRecoverySweep sweep = mock(ConsumptionRecoverySweep.class);
        when(sweep.lastReconciliation())
                .thenReturn(new ConsumptionRecoverySweep.Reconciliation(1, 2, 3));

        var queue = new ConsumptionQueueService(repo, sweep, props()).queue(50);

        assertEquals(1, queue.reconciliation().orphansRestaged());
        assertEquals(2, queue.reconciliation().rowsWithoutFile());
        assertEquals(3, queue.reconciliation().misplacedFailed());
        assertEquals(3, queue.stateCounts().get("failed"));
    }
}
