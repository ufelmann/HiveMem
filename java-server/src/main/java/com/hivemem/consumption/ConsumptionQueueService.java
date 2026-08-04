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

    private final ConsumptionFileRepository repo;
    private final ConsumptionRecoverySweep sweep;
    private final ConsumptionProperties props;

    public ConsumptionQueueService(ConsumptionFileRepository repo, ConsumptionRecoverySweep sweep,
                                   ConsumptionProperties props) {
        this.repo = repo;
        this.sweep = sweep;
        this.props = props;
    }

    public Queue queue(int limit) {
        int staleSeconds = (int) props.getRecoveryStaleThreshold().toSeconds();
        return new Queue(
                repo.findFailedNewestFirst(limit),
                repo.findDegradedBatches(props.getMinDegradedPages(), props.getBlankRatioAlert(), limit),
                repo.findStalledRows(staleSeconds, limit),
                sweep.lastReconciliation(),
                repo.countsByState());
    }

    /** @param stalledRows rows still 'staged'/'processing' past the recovery stale threshold — work
     *                     that neither finished nor failed, and which is otherwise invisible except
     *                     as an anonymous integer in {@code stateCounts}. */
    public record Queue(List<ConsumptionFileRepository.Row> failedFiles,
                        List<ConsumptionFileRepository.DegradedBatch> degradedBatches,
                        List<ConsumptionFileRepository.StalledRow> stalledRows,
                        ConsumptionRecoverySweep.Reconciliation reconciliation,
                        Map<String, Integer> stateCounts) {}
}
