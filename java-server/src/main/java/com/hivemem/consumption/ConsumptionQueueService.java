package com.hivemem.consumption;

import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Assembles the file and page levels of the ingest review queue. The cell level already exists as
 *  pending approvals and is not duplicated here. */
@Service
@ConditionalOnProperty(name = "hivemem.consumption.enabled", havingValue = "true")
public class ConsumptionQueueService {

    /** A batch needs BOTH at least this many degraded pages AND more than 2 % of its pages
     *  degraded before it is worth a human's attention. See ConsumptionQueueServiceTest. */
    static final int MIN_DEGRADED_PAGES = 2;

    private final ConsumptionFileRepository repo;
    private final ConsumptionRecoverySweep sweep;

    public ConsumptionQueueService(ConsumptionFileRepository repo, ConsumptionRecoverySweep sweep) {
        this.repo = repo;
        this.sweep = sweep;
    }

    public Queue queue(int limit) {
        return new Queue(
                repo.findRetriableFailed(Integer.MAX_VALUE, limit),
                repo.findDegradedBatches(MIN_DEGRADED_PAGES, limit),
                sweep.lastReconciliation(),
                repo.countsByState());
    }

    public record Queue(List<ConsumptionFileRepository.Row> failedFiles,
                        List<ConsumptionFileRepository.DegradedBatch> degradedBatches,
                        ConsumptionRecoverySweep.Reconciliation reconciliation,
                        Map<String, Integer> stateCounts) {}
}
