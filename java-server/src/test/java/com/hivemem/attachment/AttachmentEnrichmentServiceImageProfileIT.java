package com.hivemem.attachment;

import com.hivemem.extraction.ExtractionProfile;
import com.hivemem.extraction.ExtractionProfileRegistry;
import com.hivemem.write.WriteToolService;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Mockito-based test for the image-profile branch of
 * {@link AttachmentEnrichmentService#describeAndRevise}. We do not need a
 * Postgres container here — the SUT only touches the DB through one
 * {@code dsl.execute("UPDATE cells ...")} call which we capture via the mock.
 */
class AttachmentEnrichmentServiceImageProfileIT {

    private AttachmentProperties props;
    private VisionClient visionClient;
    private SeaweedFsClient seaweed;
    private AttachmentRepository attachmentRepo;
    private WriteToolService writeService;
    private DSLContext dsl;
    private ExtractionProfileRegistry registry;
    private VisionBudgetTracker budget;
    private AttachmentEnrichmentService svc;

    @BeforeEach
    void setUp() throws Exception {
        props = new AttachmentProperties();
        props.setEnabled(true);
        props.setAnthropicApiKey("k");
        props.setVisionDailyBudgetUsd(10.0);

        visionClient = mock(VisionClient.class);
        seaweed = mock(SeaweedFsClient.class);
        attachmentRepo = mock(AttachmentRepository.class);
        writeService = mock(WriteToolService.class);
        dsl = mock(DSLContext.class);
        registry = new ExtractionProfileRegistry();
        budget = mock(VisionBudgetTracker.class);

        when(visionClient.isEnabled()).thenReturn(true);
        when(budget.canSpend()).thenReturn(true);
        when(seaweed.download(anyString()))
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(writeService.reviseCell(any(), any(), anyString(), any()))
                .thenReturn(Map.of("new_id", UUID.randomUUID().toString()));

        svc = new AttachmentEnrichmentService(
                props, /*krokiClient*/ mock(KrokiClient.class), visionClient,
                seaweed, attachmentRepo, writeService, dsl,
                registry, budget);
    }

    @Test
    void whiteboardSubType_writesContentAndTagsCorrectly() {
        UUID attId = UUID.randomUUID();
        UUID cellId = UUID.randomUUID();
        when(visionClient.describeImage(any(), eq("image/png")))
                .thenReturn(new VisionClient.ImageDescriptionResult(
                        "whiteboard_photo",
                        "Sprint planning notes:\n- TLS rollout\n- DB migration",
                        200, 80));

        svc.describeAndRevise(attId, cellId, "img-key", "image/png");

        ArgumentCaptor<String> contentCap = ArgumentCaptor.forClass(String.class);
        verify(writeService).reviseCell(any(), eq(cellId), contentCap.capture(), isNull());
        assertTrue(contentCap.getValue().contains("Sprint planning notes"));

        verify(budget).recordCall(200, 80);

        ExtractionProfile profile = registry.resolveImageSubType("whiteboard_photo");
        assertEquals("image-whiteboard", profile.type());
        for (String t : profile.tagsToApply()) {
            verify(dsl).execute(contains("array_append"), eq(t), eq(t), eq(cellId));
        }
        verify(dsl).execute(contains("array_append"),
                eq("subtype_whiteboard_photo"), eq("subtype_whiteboard_photo"), eq(cellId));
        verify(dsl).execute(contains("array_remove"), eq("vision_pending"), eq(cellId));
    }

    @Test
    void documentScanSubType_appliesDocumentTags() {
        UUID cellId = UUID.randomUUID();
        when(visionClient.describeImage(any(), anyString()))
                .thenReturn(new VisionClient.ImageDescriptionResult(
                        "document_scan", "Sehr geehrte Damen und Herren ...", 300, 200));

        svc.describeAndRevise(UUID.randomUUID(), cellId, "k", "image/jpeg");

        verify(dsl).execute(contains("array_append"),
                eq("subtype_document_scan"), eq("subtype_document_scan"), eq(cellId));
        verify(dsl).execute(contains("array_append"),
                eq("document"), eq("document"), eq(cellId));
        verify(dsl).execute(contains("array_append"),
                eq("has_text"), eq("has_text"), eq(cellId));
    }

    @Test
    void photoGeneralSubType_appliesPhotoTag() {
        UUID cellId = UUID.randomUUID();
        when(visionClient.describeImage(any(), anyString()))
                .thenReturn(new VisionClient.ImageDescriptionResult(
                        "photo_general", "A golden retriever in a park.", 100, 30));

        svc.describeAndRevise(UUID.randomUUID(), cellId, "k", "image/jpeg");

        verify(dsl).execute(contains("array_append"),
                eq("subtype_photo_general"), eq("subtype_photo_general"), eq(cellId));
        verify(dsl).execute(contains("array_append"),
                eq("photo"), eq("photo"), eq(cellId));
    }

    @Test
    void cleansOldSubtypeTagsBeforeApplyingNew() {
        UUID cellId = UUID.randomUUID();
        when(visionClient.describeImage(any(), anyString()))
                .thenReturn(new VisionClient.ImageDescriptionResult(
                        "whiteboard_photo", "x", 10, 10));

        svc.describeAndRevise(UUID.randomUUID(), cellId, "k", "image/png");

        verify(dsl).execute(contains("array_remove"),
                eq("subtype_whiteboard_photo"), eq("subtype_document_scan"),
                eq("subtype_photo_general"), eq(cellId));
    }
}
