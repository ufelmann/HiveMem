package com.hivemem.summarize;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.write.WriteToolService;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "hivemem.summarize.enabled", havingValue = "true")
public class SummarizerService {

    private static final Logger log = LoggerFactory.getLogger(SummarizerService.class);
    private static final AuthPrincipal SYSTEM_PRINCIPAL =
            new AuthPrincipal("system-summarizer", AuthRole.ADMIN);

    private final SummarizerProperties props;
    private final SummarizerRepository repo;
    private final SummarizeBudgetTracker budget;
    private final AnthropicSummarizer anthropic;
    private final WriteToolService writeService;

    public SummarizerService(SummarizerProperties props,
                             SummarizerRepository repo,
                             DSLContext dsl,
                             RestClient.Builder builder,
                             WriteToolService writeService) {
        this.props = props;
        this.repo = repo;
        this.budget = new SummarizeBudgetTracker(dsl, props.getDailyBudgetUsd());
        this.anthropic = new AnthropicSummarizer(
                builder, props.getAnthropicApiKey(), props.getModel(),
                props.getCallTimeoutSeconds(), props.getMaxInputChars());
        this.writeService = writeService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCellNeedsSummary(CellNeedsSummaryEvent event) {
        if (!budget.canSpend()) {
            log.info("Summarize budget exhausted; deferring cell {}", event.cellId());
            return;
        }
        summarizeOne(event.cellId());
    }

    @Scheduled(fixedRateString = "${hivemem.summarize.backfill-interval-ms:300000}")
    public void backfill() {
        if (!budget.canSpend()) return;
        List<UUID> ids = repo.findCellsNeedingSummary(props.getBackfillBatchSize());
        for (UUID id : ids) {
            if (!budget.canSpend()) break;
            summarizeOne(id);
        }
    }

    void summarizeOne(UUID cellId) {
        var snap = repo.findCellSnapshot(cellId).orElse(null);
        if (snap == null) return;
        if (snap.summary() != null && !snap.summary().isBlank()) {
            // Already summarized by another path; clean up tag and exit.
            repo.removeNeedsSummaryTag(cellId);
            return;
        }
        if (snap.content() == null || snap.content().isBlank()) {
            repo.removeNeedsSummaryTag(cellId);
            return;
        }

        try {
            SummaryResult result = anthropic.summarize(snap.content());
            budget.recordCall(result.inputTokens(), result.outputTokens());

            // Push through WriteToolService.reviseCell so the embedding is regenerated
            // (via encodeForCell) and ops_log is updated for sync replication.
            var reviseResult = writeService.reviseCell(SYSTEM_PRINCIPAL, cellId, snap.content(), result.summary());
            // reviseCell supersedes the old cell and creates a new row that inherits tags
            // (including needs_summary). Remove needs_summary from both the old and new rows.
            repo.removeNeedsSummaryTag(cellId);
            Object newIdObj = reviseResult.get("new_id");
            if (newIdObj != null) {
                try {
                    repo.removeNeedsSummaryTag(UUID.fromString(newIdObj.toString()));
                } catch (IllegalArgumentException ignored) {
                    // not a valid UUID string — skip
                }
            }
        } catch (HttpClientErrorException.TooManyRequests e) {
            log.warn("Anthropic 429 for cell {}, marking throttled", cellId);
            repo.tagThrottled(cellId);
        } catch (Exception e) {
            log.warn("Summarize failed for cell {}: {}", cellId, e.getMessage());
        }
    }
}
