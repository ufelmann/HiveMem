package com.hivemem.summarize;

import com.hivemem.consumption.DocumentDedupService;
import com.hivemem.extraction.ExtractionProfileRegistry;
import com.hivemem.extraction.ExtractionProperties;
import com.hivemem.llm.LlmCallCost;
import com.hivemem.queen.ArchivistTrigger;
import com.hivemem.write.WriteToolService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies SummarizerService fires the on-demand archivist trigger once a cell settles after
 * summarization (or gives up trying) -- the gap this closes: long/OCR'd documents previously only
 * settled via the daily cron, since only OcrService/AttachmentEnrichmentService called the trigger.
 *
 * <p>Also pins the cost-accounting contract of the summarize path: the record the PROVIDER
 * reported (routed provider/model, all four token kinds) is what reaches the budget tracker and
 * the log line -- not HiveMem's requested model with cache tokens dropped.
 */
@ExtendWith(OutputCaptureExtension.class)
class SummarizerServiceTriggerTest {

    private final SummarizerProperties props = new SummarizerProperties();
    private final ExtractionProperties extractionProps = new ExtractionProperties();
    private final SummarizerRepository repo = mock(SummarizerRepository.class);
    private final WriteToolService writeService = mock(WriteToolService.class);
    private final ExtractionProfileRegistry registry = mock(ExtractionProfileRegistry.class);
    private final AnthropicSummarizer anthropic = mock(AnthropicSummarizer.class);
    private final SummarizeBudgetTracker budget = mock(SummarizeBudgetTracker.class);
    private final DocumentDedupService dedup = mock(DocumentDedupService.class);
    private final ArchivistTrigger trigger = mock(ArchivistTrigger.class);

    private final UUID cellId = UUID.randomUUID();
    private SummarizerService service;

    @BeforeEach
    void setUp() {
        when(repo.tryClaim(cellId)).thenReturn(true);
        when(repo.findCellSnapshot(cellId)).thenReturn(Optional.of(
                new SummarizerRepository.CellSnapshot(cellId, "long scanned text", null,
                        List.of(), null, List.of())));
        when(repo.findCellAttachmentMeta(any())).thenReturn(Optional.empty());

        service = new SummarizerService(
                props, extractionProps, repo, budget, anthropic, writeService, registry, dedup);
        service.archivistTrigger = trigger; // package-private test seam (mirrors OcrService)
    }

    @Test
    void givesUpAndNotifiesArchivistWhenSummarizerProducesNoSummary() {
        // Empty summary -> the "give up" branch, which removes needs_summary but must still
        // notify the archivist since the cell is now settled (no other tag will fire it).
        when(anthropic.summarize(any(), any())).thenReturn(
                new SummaryResult(null, null, List.of(), null, List.of(), null, List.of(), null, false,
                        LlmCallCost.ZERO));

        service.summarizeOne(cellId);

        verify(trigger).maybeTrigger(cellId);
    }

    @Test
    void logsTheRoutedModelAndEurCost(CapturedOutput out) {
        when(anthropic.summarize(any(), any())).thenReturn(new SummaryResult(
                "t", "a summary", List.of(), null, List.of(), "other", List.of(), "de", false,
                new LlmCallCost("claude-subscription", "claude-sonnet-5", 2, 1487, 25681, 0, 0L)));
        when(budget.recordCall(any())).thenReturn(new BigDecimal("0.000000"));

        service.summarizeOne(cellId);

        assertThat(out).contains("provider=claude-subscription")
                .contains("model=claude-sonnet-5")
                .contains("cost=€");
        assertThat(out).doesNotContain("cost=$");
    }

    @Test
    void logsAllFourTokenKindsFromTheProvidersRecord(CapturedOutput out) {
        when(anthropic.summarize(any(), any())).thenReturn(new SummaryResult(
                "t", "a summary", List.of(), null, List.of(), "other", List.of(), "de", false,
                new LlmCallCost("bedrock", "claude-haiku-4-5", 2, 1487, 25681, 4096, 2343L)));
        when(budget.recordCall(any())).thenReturn(new BigDecimal("0.002343"));

        service.summarizeOne(cellId);

        assertThat(out).contains("in=2").contains("cacheW=25681").contains("cacheR=4096")
                .contains("out=1487")
                .contains("cost=€0.002343");
    }

    /**
     * The bug this pins: booking {@code props.getModel()} with the cache tokens zeroed instead of
     * passing the provider's own record through. Captured, not merely verified, so a hand-built
     * substitute record fails here.
     */
    @Test
    void booksTheProvidersOwnCostRecordUnchanged() {
        var provided = new LlmCallCost("claude-subscription", "claude-sonnet-5",
                2, 1487, 25681, 4096, 2343L);
        when(anthropic.summarize(any(), any())).thenReturn(new SummaryResult(
                "t", "a summary", List.of(), null, List.of(), "other", List.of(), "de", false,
                provided));
        when(budget.recordCall(any())).thenReturn(new BigDecimal("0.002343"));

        service.summarizeOne(cellId);

        ArgumentCaptor<LlmCallCost> booked = ArgumentCaptor.forClass(LlmCallCost.class);
        verify(budget).recordCall(booked.capture());
        LlmCallCost c = booked.getValue();
        assertThat(c.provider()).as("routed provider, not a hand-built one").isEqualTo("claude-subscription");
        assertThat(c.model()).as("routed model, not props.getModel()").isEqualTo("claude-sonnet-5");
        assertThat(c.inputTokens()).isEqualTo(2);
        assertThat(c.outputTokens()).isEqualTo(1487);
        assertThat(c.cacheCreationTokens()).as("cache-write tokens must not be dropped").isEqualTo(25681);
        assertThat(c.cacheReadTokens()).as("cache-read tokens must not be dropped").isEqualTo(4096);
        assertThat(c.costMicros()).isEqualTo(2343L);
        assertThat(c.totalInputTokens()).isEqualTo(29779);
    }
}
