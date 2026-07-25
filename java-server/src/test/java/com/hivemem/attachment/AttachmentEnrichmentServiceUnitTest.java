package com.hivemem.attachment;

import com.hivemem.extraction.ExtractionProfile;
import com.hivemem.extraction.ExtractionProfileRegistry;
import com.hivemem.llm.LlmCallCost;
import com.hivemem.queen.ArchivistTrigger;
import com.hivemem.write.WriteToolService;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit-level tests for AttachmentEnrichmentService that exercise the
 * branch logic in event handlers, scheduled backfill, and the catch
 * blocks in describeAndRevise / renderAndStore.
 *
 * Integration paths (real Postgres + SeaweedFS) live in
 * {@link AttachmentEnrichmentServiceIT} and
 * {@link AttachmentEnrichmentServiceImageProfileIT}.
 */
@ExtendWith(OutputCaptureExtension.class)
class AttachmentEnrichmentServiceUnitTest {

    private AttachmentProperties props;
    private KrokiClient krokiClient;
    private VisionClient visionClient;
    private SeaweedFsClient seaweedFs;
    private AttachmentRepository attachmentRepo;
    private WriteToolService writeService;
    private DSLContext dsl;
    private ExtractionProfileRegistry profileRegistry;
    private VisionBudgetTracker visionBudget;
    private ArchivistTrigger archivistTrigger;
    private AttachmentEnrichmentService svc;

    @BeforeEach
    void setUp() {
        props = mock(AttachmentProperties.class);
        krokiClient = mock(KrokiClient.class);
        visionClient = mock(VisionClient.class);
        seaweedFs = mock(SeaweedFsClient.class);
        attachmentRepo = mock(AttachmentRepository.class);
        writeService = mock(WriteToolService.class);
        dsl = mock(DSLContext.class);
        profileRegistry = mock(ExtractionProfileRegistry.class);
        visionBudget = mock(VisionBudgetTracker.class);
        archivistTrigger = mock(ArchivistTrigger.class);
        lenient().when(attachmentRepo.tryClaim(any())).thenReturn(true); // open the concurrency-claim gate
        svc = new AttachmentEnrichmentService(props, krokiClient, visionClient, seaweedFs,
                attachmentRepo, writeService, dsl, profileRegistry, visionBudget, archivistTrigger);
    }

    // ── onThumbnailRequested ───────────────────────────────────────────────

    @Test
    void onThumbnailRequested_noOpWhenKrokiDisabled() {
        when(krokiClient.isEnabled()).thenReturn(false);

        svc.onThumbnailRequested(new ThumbnailRequestedEvent(
                UUID.randomUUID(), UUID.randomUUID(), "hash", "text/x-mermaid", "graph TD; A-->B"));

        verifyNoInteractions(seaweedFs, attachmentRepo, dsl);
    }

    // ── onVisionRequested ──────────────────────────────────────────────────

    @Test
    void onVisionRequested_noOpWhenVisionDisabled() {
        when(visionClient.isEnabled()).thenReturn(false);

        svc.onVisionRequested(new VisionDescriptionRequestedEvent(
                UUID.randomUUID(), UUID.randomUUID(), "key", "image/png"));

        verifyNoInteractions(seaweedFs);
        verify(visionBudget, never()).canSpend();
    }

    @Test
    void onVisionRequested_skippedWhenBudgetExhausted() {
        when(visionClient.isEnabled()).thenReturn(true);
        when(visionBudget.canSpend()).thenReturn(false);

        svc.onVisionRequested(new VisionDescriptionRequestedEvent(
                UUID.randomUUID(), UUID.randomUUID(), "key", "image/png"));

        verifyNoInteractions(seaweedFs);
    }

    // ── backfillThumbnails ─────────────────────────────────────────────────

    @Test
    void backfillThumbnails_noOpWhenKrokiDisabled() {
        when(krokiClient.isEnabled()).thenReturn(false);
        svc.backfillThumbnails();
        verifyNoInteractions(attachmentRepo);
    }

    @Test
    void backfillThumbnails_emptyListShortCircuits() {
        when(krokiClient.isEnabled()).thenReturn(true);
        when(attachmentRepo.findDiagramsWithoutThumbnail(any(Set.class), eq(50)))
                .thenReturn(List.of());

        svc.backfillThumbnails();

        verify(attachmentRepo).findDiagramsWithoutThumbnail(any(Set.class), eq(50));
        verifyNoInteractions(seaweedFs);
    }

    @Test
    void backfillThumbnails_processesEachRow() {
        when(krokiClient.isEnabled()).thenReturn(true);
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();
        when(attachmentRepo.findDiagramsWithoutThumbnail(any(Set.class), eq(50)))
                .thenReturn(List.of(
                        new AttachmentRepository.DiagramRow(a1, UUID.randomUUID(), "h1", "text/x-mermaid", "src1"),
                        new AttachmentRepository.DiagramRow(a2, UUID.randomUUID(), "h2", "text/x-mermaid", "src2")));
        when(krokiClient.render(anyString(), anyString())).thenReturn(Optional.empty());

        svc.backfillThumbnails();

        verify(krokiClient, times(2)).render(anyString(), anyString());
    }

    // ── backfillVisionDescriptions ─────────────────────────────────────────

    @Test
    void backfillVision_noOpWhenVisionDisabled() {
        when(visionClient.isEnabled()).thenReturn(false);
        svc.backfillVisionDescriptions();
        verifyNoInteractions(attachmentRepo);
    }

    @Test
    void backfillVision_noOpWhenBudgetExhausted() {
        when(visionClient.isEnabled()).thenReturn(true);
        when(visionBudget.canSpend()).thenReturn(false);
        svc.backfillVisionDescriptions();
        verifyNoInteractions(attachmentRepo);
    }

    @Test
    void backfillVision_emptyListShortCircuits() {
        when(visionClient.isEnabled()).thenReturn(true);
        when(visionBudget.canSpend()).thenReturn(true);
        when(attachmentRepo.findCellsWithVisionPending(20)).thenReturn(List.of());

        svc.backfillVisionDescriptions();

        verify(attachmentRepo).findCellsWithVisionPending(20);
        verify(attachmentRepo, never()).findAttachmentForCell(any());
    }

    @Test
    void backfillVision_breaksWhenBudgetDuringLoop() {
        when(visionClient.isEnabled()).thenReturn(true);
        // Allow first call (entry), but exhaust before processing second cell.
        when(visionBudget.canSpend()).thenReturn(true, true, false);
        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();
        when(attachmentRepo.findCellsWithVisionPending(20)).thenReturn(List.of(c1, c2));
        when(attachmentRepo.findAttachmentForCell(c1)).thenReturn(Optional.empty());

        svc.backfillVisionDescriptions();

        // Loop entered c1, but c2 was skipped because budget went negative before its iteration.
        verify(attachmentRepo).findAttachmentForCell(c1);
        verify(attachmentRepo, never()).findAttachmentForCell(c2);
    }

    @Test
    void backfillVision_skipsCellsWithoutAttachment() {
        when(visionClient.isEnabled()).thenReturn(true);
        when(visionBudget.canSpend()).thenReturn(true);
        UUID c1 = UUID.randomUUID();
        when(attachmentRepo.findCellsWithVisionPending(20)).thenReturn(List.of(c1));
        when(attachmentRepo.findAttachmentForCell(c1)).thenReturn(Optional.empty());

        svc.backfillVisionDescriptions();

        verifyNoInteractions(seaweedFs);
    }

    // ── renderAndStore ─────────────────────────────────────────────────────

    @Test
    void renderAndStore_storesThumbnailOnSuccess() {
        UUID att = UUID.randomUUID();
        UUID cell = UUID.randomUUID();
        byte[] png = new byte[]{1, 2, 3};
        when(krokiClient.render(eq("text/x-mermaid"), eq("src"))).thenReturn(Optional.of(png));

        svc.renderAndStore(att, cell, "hash", "text/x-mermaid", "src");

        verify(seaweedFs).uploadBytes(eq("hash-thumb.png"), eq(png), eq("image/png"));
        verify(attachmentRepo).updateThumbnailKey(att, "hash-thumb.png");
        // removeTag(kroki_pending): 3-arg execute.
        verify(dsl).execute(anyString(), eq("kroki_pending"), eq(cell));
    }

    @Test
    void renderAndStore_tagsFailedWhenRenderEmpty() {
        UUID cell = UUID.randomUUID();
        when(krokiClient.render(anyString(), anyString())).thenReturn(Optional.empty());

        svc.renderAndStore(UUID.randomUUID(), cell, "hash", "text/x-mermaid", "src");

        // applyTag(kroki_failed): 4-arg execute. removeTag(kroki_pending): 3-arg execute.
        verify(dsl).execute(anyString(), eq("kroki_failed"), eq("kroki_failed"), eq(cell));
        verify(dsl).execute(anyString(), eq("kroki_pending"), eq(cell));
    }

    @Test
    void renderAndStore_uploadFailureLeavesNoTagSwap() {
        UUID cell = UUID.randomUUID();
        when(krokiClient.render(anyString(), anyString())).thenReturn(Optional.of(new byte[]{1}));
        org.mockito.Mockito.doThrow(new RuntimeException("s3 down"))
                .when(seaweedFs).uploadBytes(anyString(), any(), anyString());

        svc.renderAndStore(UUID.randomUUID(), cell, "hash", "text/x-mermaid", "src");

        verify(attachmentRepo, never()).updateThumbnailKey(any(), anyString());
        // Inner catch swallows; pending tag stays — no remove call.
        verify(dsl, never()).execute(anyString(), eq("kroki_pending"), eq(cell));
    }

    @Test
    void renderAndStore_outerExceptionSwallowed() {
        when(krokiClient.render(anyString(), anyString())).thenThrow(new RuntimeException("network"));

        svc.renderAndStore(UUID.randomUUID(), UUID.randomUUID(), "hash", "text/x-mermaid", "src");

        verifyNoInteractions(seaweedFs, attachmentRepo);
    }

    // ── describeAndRevise ──────────────────────────────────────────────────

    @Test
    void describeAndRevise_returnsEarlyOnDownloadFailure() {
        when(seaweedFs.download("k")).thenThrow(new RuntimeException("missing"));

        svc.describeAndRevise(UUID.randomUUID(), UUID.randomUUID(), "k", "image/png");

        verifyNoInteractions(visionClient, writeService, profileRegistry);
        verify(visionBudget, never()).recordCall(any());
    }

    @Test
    void describeAndRevise_tagsFailedOnBlankContent() {
        UUID cell = UUID.randomUUID();
        stubDownload();
        when(visionClient.describeImage(any(), eq("image/png")))
                .thenReturn(new VisionClient.ImageDescriptionResult("photo_general", "  ", cost(5, 1)));

        svc.describeAndRevise(UUID.randomUUID(), cell, "k", "image/png");

        verify(visionBudget).recordCall(cost(5, 1));
        verify(writeService, never()).reviseCell(any(), any(), any(), any());
        // applyTag(vision_failed): 4-arg execute. removeTag(vision_pending): 3-arg execute.
        verify(dsl).execute(anyString(), eq("vision_failed"), eq("vision_failed"), eq(cell));
        verify(dsl).execute(anyString(), eq("vision_pending"), eq(cell));
    }

    @Test
    void describeAndRevise_writesContentAndAppliesProfileTags() {
        UUID cell = UUID.randomUUID();
        stubDownload();
        when(visionClient.describeImage(any(), eq("image/png")))
                .thenReturn(new VisionClient.ImageDescriptionResult(
                        "whiteboard_photo", "A drawing", cost(10, 5)));
        ExtractionProfile profile = new ExtractionProfile(
                "image", "p", null, null, null, List.of("contains_text", "structured"));
        when(profileRegistry.resolveImageSubType("whiteboard_photo")).thenReturn(profile);

        svc.describeAndRevise(UUID.randomUUID(), cell, "k", "image/png");

        verify(writeService).reviseCell(any(), eq(cell), eq("A drawing"), eq(null));
        // cleanOldSubtypeTags: 5-arg (sql + 3 subtype tags + cellId).
        verify(dsl).execute(anyString(),
                eq("subtype_whiteboard_photo"), eq("subtype_document_scan"),
                eq("subtype_photo_general"), eq(cell));
        // applyTag for subtype_whiteboard_photo + 2 profile tags = 3 × 4-arg execute.
        verify(dsl).execute(anyString(), eq("subtype_whiteboard_photo"), eq("subtype_whiteboard_photo"), eq(cell));
        verify(dsl).execute(anyString(), eq("contains_text"), eq("contains_text"), eq(cell));
        verify(dsl).execute(anyString(), eq("structured"), eq("structured"), eq(cell));
        // removeTag(vision_pending): 3-arg execute.
        verify(dsl).execute(anyString(), eq("vision_pending"), eq(cell));
    }

    @Test
    void describeAndRevise_targetsNewRevisionIdFromReviseResult() {
        UUID oldCell = UUID.randomUUID();
        UUID newCell = UUID.randomUUID();
        stubDownload();
        when(visionClient.describeImage(any(), eq("image/png")))
                .thenReturn(new VisionClient.ImageDescriptionResult("photo_general", "A photo", cost(10, 5)));
        when(writeService.reviseCell(any(), eq(oldCell), eq("A photo"), eq(null)))
                .thenReturn(java.util.Map.<String, Object>of("new_id", newCell));
        ExtractionProfile profile = new ExtractionProfile("image", "p", null, null, null, List.of());
        when(profileRegistry.resolveImageSubType("photo_general")).thenReturn(profile);

        svc.describeAndRevise(UUID.randomUUID(), oldCell, "k", "image/png");

        // Subtype tag work must land on the NEW revision, not the superseded (dead) cell.
        verify(dsl).execute(anyString(),
                eq("subtype_whiteboard_photo"), eq("subtype_document_scan"),
                eq("subtype_photo_general"), eq(newCell));
        verify(dsl).execute(anyString(),
                eq("subtype_photo_general"), eq("subtype_photo_general"), eq(newCell));
        // vision_pending removed from BOTH the old cell and the new revision, or the
        // hourly backfill re-describes the live revision forever.
        verify(dsl).execute(anyString(), eq("vision_pending"), eq(oldCell));
        verify(dsl).execute(anyString(), eq("vision_pending"), eq(newCell));
    }

    @Test
    void describeAndRevise_swallows429ToRetryLater() {
        UUID cell = UUID.randomUUID();
        stubDownload();
        when(visionClient.describeImage(any(), anyString()))
                .thenThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                        "429", org.springframework.http.HttpHeaders.EMPTY, new byte[0], null));

        svc.describeAndRevise(UUID.randomUUID(), cell, "k", "image/png");

        verify(writeService, never()).reviseCell(any(), any(), any(), any());
        verify(dsl, never()).execute(anyString(), any(), any());
        verify(dsl, never()).execute(anyString(), any(), any(), any());
    }

    @Test
    void describeAndRevise_tagsFailedOnOversize() {
        UUID cell = UUID.randomUUID();
        stubDownload();
        when(visionClient.describeImage(any(), anyString()))
                .thenThrow(new VisionClient.OversizeImageException("too big"));

        svc.describeAndRevise(UUID.randomUUID(), cell, "k", "image/png");

        verify(dsl).execute(anyString(), eq("vision_failed"), eq("vision_failed"), eq(cell));
        verify(dsl).execute(anyString(), eq("vision_pending"), eq(cell));
    }

    @Test
    void describeAndRevise_tagsFailedOnUnsupportedMime() {
        UUID cell = UUID.randomUUID();
        stubDownload();
        when(visionClient.describeImage(any(), anyString()))
                .thenThrow(new IllegalArgumentException("mime not supported"));

        svc.describeAndRevise(UUID.randomUUID(), cell, "k", "image/bmp");

        verify(dsl).execute(anyString(), eq("vision_failed"), eq("vision_failed"), eq(cell));
        verify(dsl).execute(anyString(), eq("vision_pending"), eq(cell));
    }

    @Test
    void describeAndRevise_genericExceptionSwallowed() {
        UUID cell = UUID.randomUUID();
        stubDownload();
        when(visionClient.describeImage(any(), anyString()))
                .thenThrow(new RuntimeException("upstream blew up"));

        svc.describeAndRevise(UUID.randomUUID(), cell, "k", "image/png");

        verify(writeService, never()).reviseCell(any(), any(), any(), any());
        // Generic exception should not tag failed (might be transient — backfill retries).
        verify(dsl, never()).execute(anyString(), any(), any());
        verify(dsl, never()).execute(anyString(), any(), any(), any());
    }


    /**
     * The tracker must receive the PROVIDER's own record, not one rebuilt from the model
     * HiveMem requested with the cache tokens zeroed: Vistierie may route elsewhere, and with
     * prompt caching on the bulk of the input sits in the cache fields. Rebuilding would book a
     * wrong model and under-count total_input_tokens by orders of magnitude.
     */
    @Test
    void describeAndRevise_booksTheProvidersOwnCostRecordUnchanged() {
        stubDownload();
        LlmCallCost fromProvider = new LlmCallCost(
                "claude-subscription", "claude-sonnet-5", 1500, 300, 4000, 21000, 2343L);
        when(visionClient.describeImage(any(), eq("image/png")))
                .thenReturn(new VisionClient.ImageDescriptionResult(
                        "photo_general", "A photo", fromProvider));
        when(profileRegistry.resolveImageSubType(anyString()))
                .thenReturn(new ExtractionProfile("image", "p", null, null, null, List.of()));

        svc.describeAndRevise(UUID.randomUUID(), UUID.randomUUID(), "k", "image/png");

        ArgumentCaptor<LlmCallCost> booked = ArgumentCaptor.forClass(LlmCallCost.class);
        verify(visionBudget).recordCall(booked.capture());
        assertThat(booked.getValue()).isSameAs(fromProvider);
    }

    /**
     * Acceptance criterion 4 of the cost-accounting spec: the vision per-call INFO line has to
     * name the provider and the model Vistierie actually ROUTED to, every token kind, and the
     * amount {@code recordCall} booked — mirroring the summarize line
     * ({@code SummarizerServiceTriggerTest.logsAllFourTokenKindsFromTheProvidersRecord}).
     */
    @Test
    void describeAndRevise_logsRoutedProviderModelTokensAndBookedCost(CapturedOutput out) {
        stubDownload();
        when(visionClient.describeImage(any(), eq("image/png")))
                .thenReturn(new VisionClient.ImageDescriptionResult("photo_general", "A photo",
                        new LlmCallCost("bedrock", "claude-haiku-4-5", 2, 1487, 25681, 4096, 2343L)));
        when(profileRegistry.resolveImageSubType(anyString()))
                .thenReturn(new ExtractionProfile("image", "p", null, null, null, List.of()));
        when(visionBudget.recordCall(any())).thenReturn(new BigDecimal("0.002343"));
        when(visionBudget.todaySpendUsd()).thenReturn(new BigDecimal("0.004686"));
        when(visionBudget.dailyBudgetUsd()).thenReturn(5.0);

        svc.describeAndRevise(UUID.randomUUID(), UUID.randomUUID(), "k", "image/png");

        assertThat(out).contains("provider=bedrock ").contains("model=claude-haiku-4-5 ");
        // One contiguous string, not four bare substrings: "in=2" alone also matches inside
        // "in=29779" (the totalInputTokens mistake this assertion exists to catch).
        assertThat(out).contains("in=2 cacheW=25681 cacheR=4096 out=1487 ");
        // Not just "cost=€": that also passes for "cost=€null" when recordCall's return value
        // is discarded. Require the amount recordCall returned.
        assertThat(out).containsPattern("cost=€\\d+\\.\\d+ ").contains("cost=€0.002343 ");
        assertThat(out).contains("day=€0.004686/5.00");
        assertThat(out).doesNotContain("cost=$");
    }

    /**
     * Vistierie answered, and was billed, with no usable text. The cost has to be booked anyway:
     * the cell keeps {@code vision_pending}, the hourly backfill re-picks it, and every round
     * pays for a real vision call — if nothing is booked, {@code total_cost_usd} never moves and
     * the daily cap never fires, so the loop is unbounded.
     */
    @Test
    void describeAndRevise_booksTheCostOfABlankTextResponse() {
        stubDownload();
        LlmCallCost paidFor = new LlmCallCost(
                "bedrock", "claude-haiku-4-5", 1500, 300, 4000, 21000, 2343L);
        when(visionClient.describeImage(any(), anyString())).thenThrow(
                new VisionClient.EmptyResponseException("Vistierie returned empty text", paidFor));

        svc.describeAndRevise(UUID.randomUUID(), UUID.randomUUID(), "k", "image/png");

        ArgumentCaptor<LlmCallCost> booked = ArgumentCaptor.forClass(LlmCallCost.class);
        verify(visionBudget).recordCall(booked.capture());
        assertThat(booked.getValue()).isSameAs(paidFor);
    }

    /**
     * "Cost accounting must never break the functional path" (LlmCallCost's javadoc): a transient
     * Postgres error while booking must not throw away an already-paid-for description.
     */
    @Test
    void describeAndRevise_keepsTheDescriptionWhenBookingTheCostFails() {
        UUID cell = UUID.randomUUID();
        stubDownload();
        when(visionClient.describeImage(any(), eq("image/png")))
                .thenReturn(new VisionClient.ImageDescriptionResult(
                        "photo_general", "A photo", cost(10, 5)));
        when(profileRegistry.resolveImageSubType(anyString()))
                .thenReturn(new ExtractionProfile("image", "p", null, null, null, List.of()));
        when(visionBudget.recordCall(any()))
                .thenThrow(new org.jooq.exception.DataAccessException("connection reset"));

        svc.describeAndRevise(UUID.randomUUID(), cell, "k", "image/png");

        verify(writeService).reviseCell(any(), eq(cell), eq("A photo"), eq(null));
        verify(dsl).execute(anyString(), eq("vision_pending"), eq(cell));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void stubDownload() {
        InputStream is = new ByteArrayInputStream(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});
        lenient().when(seaweedFs.download(anyString())).thenReturn(is);
    }

    /** A stand-in Vistierie cost record; the vision tracker is mocked, only identity matters. */
    private static LlmCallCost cost(int inputTokens, int outputTokens) {
        return new LlmCallCost("bedrock", "claude-haiku-4-5", inputTokens, outputTokens, 0, 0, 1L);
    }
}
