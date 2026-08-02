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
    @Test
    void aSingleDegradedPageDoesNotReachTheQueue() {
        ConsumptionFileRepository repo = mock(ConsumptionFileRepository.class);
        when(repo.findDegradedBatches(anyInt(), anyInt())).thenReturn(List.of());
        when(repo.countsByState()).thenReturn(Map.of("done", 20));
        ConsumptionRecoverySweep sweep = mock(ConsumptionRecoverySweep.class);
        when(sweep.lastReconciliation())
                .thenReturn(new ConsumptionRecoverySweep.Reconciliation(0, 0, 0));

        var queue = new ConsumptionQueueService(repo, sweep).queue(50);

        assertTrue(queue.degradedBatches().isEmpty());
        verify(repo).findDegradedBatches(2, 50);   // minimum 2 degraded pages
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

        var queue = new ConsumptionQueueService(repo, sweep).queue(50);

        assertEquals(1, queue.reconciliation().orphansRestaged());
        assertEquals(2, queue.reconciliation().rowsWithoutFile());
        assertEquals(3, queue.reconciliation().misplacedFailed());
        assertEquals(3, queue.stateCounts().get("failed"));
    }
}
