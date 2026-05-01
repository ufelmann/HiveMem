package com.hivemem.attachment;

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

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "hivemem.attachment.enabled", havingValue = "true")
public class AttachmentEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentEnrichmentService.class);
    private static final AuthPrincipal SYSTEM_PRINCIPAL =
            new AuthPrincipal("system-enrichment", AuthRole.ADMIN);

    private final AttachmentProperties props;
    private final KrokiClient krokiClient;
    private final VisionClient visionClient;
    private final VisionBudgetTracker visionBudget;
    private final SeaweedFsClient seaweedFs;
    private final AttachmentRepository attachmentRepo;
    private final WriteToolService writeService;
    private final DSLContext dsl;

    public AttachmentEnrichmentService(AttachmentProperties props,
                                       KrokiClient krokiClient,
                                       VisionClient visionClient,
                                       SeaweedFsClient seaweedFs,
                                       AttachmentRepository attachmentRepo,
                                       WriteToolService writeService,
                                       DSLContext dsl) {
        this.props = props;
        this.krokiClient = krokiClient;
        this.visionClient = visionClient;
        this.visionBudget = new VisionBudgetTracker(dsl, props.getVisionDailyBudgetUsd());
        this.seaweedFs = seaweedFs;
        this.attachmentRepo = attachmentRepo;
        this.writeService = writeService;
        this.dsl = dsl;
    }

    // ── Event listeners (after-commit) ─────────────────────────────────────

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onThumbnailRequested(ThumbnailRequestedEvent ev) {
        if (!krokiClient.isEnabled()) return;
        renderAndStore(ev.attachmentId(), ev.cellId(), ev.fileHash(), ev.mimeType(), ev.diagramSource());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVisionRequested(VisionDescriptionRequestedEvent ev) {
        if (!visionClient.isEnabled()) return;
        if (!visionBudget.canSpend()) {
            log.info("Vision budget exhausted; deferring cell {}", ev.cellId());
            return;
        }
        describeAndRevise(ev.attachmentId(), ev.cellId(), ev.s3KeyOriginal(), ev.mimeType());
    }

    // ── Backfill (scheduled hourly) ────────────────────────────────────────

    @Scheduled(fixedRateString = "${hivemem.attachment.kroki-backfill-interval-ms:3600000}")
    public void backfillThumbnails() {
        if (!krokiClient.isEnabled()) return;
        List<AttachmentRepository.DiagramRow> rows =
                attachmentRepo.findDiagramsWithoutThumbnail(KrokiClient.MIME_TO_FORMAT.keySet(), 50);
        if (rows.isEmpty()) return;
        log.info("Kroki backfill: {} diagrams pending", rows.size());
        for (var r : rows) {
            renderAndStore(r.attachmentId(), r.cellId(), r.fileHash(), r.mimeType(), r.diagramSource());
        }
    }

    @Scheduled(fixedRateString = "${hivemem.attachment.vision-backfill-interval-ms:3600000}")
    public void backfillVisionDescriptions() {
        if (!visionClient.isEnabled()) return;
        if (!visionBudget.canSpend()) return;
        List<UUID> cellIds = attachmentRepo.findCellsWithVisionPending(20);
        if (cellIds.isEmpty()) return;
        log.info("Vision backfill: {} cells pending", cellIds.size());
        for (UUID cellId : cellIds) {
            if (!visionBudget.canSpend()) break;
            attachmentRepo.findAttachmentForCell(cellId).ifPresent(att ->
                    describeAndRevise(att.attachmentId(), cellId, att.s3KeyOriginal(), att.mimeType()));
        }
    }

    // ── Internal helpers ───────────────────────────────────────────────────

    void renderAndStore(UUID attId, UUID cellId, String fileHash, String mimeType, String source) {
        try {
            krokiClient.render(mimeType, source).ifPresentOrElse(png -> {
                try {
                    String key = fileHash + "-thumb.png";
                    seaweedFs.uploadBytes(key, png, "image/png");
                    attachmentRepo.updateThumbnailKey(attId, key);
                    removeTag(cellId, "kroki_pending");
                    log.debug("Kroki thumbnail stored for attachment {}", attId);
                } catch (Exception e) {
                    log.warn("Failed to store Kroki thumbnail for {}: {}", attId, e.getMessage());
                }
            }, () -> {
                // Render returned empty (4xx / unsupported / blank): mark failed, stop retrying.
                tagFailed(cellId, "kroki_failed");
                removeTag(cellId, "kroki_pending");
            });
        } catch (Exception e) {
            log.warn("Kroki render error for attachment {}: {}", attId, e.getMessage());
        }
    }

    void describeAndRevise(UUID attId, UUID cellId, String s3KeyOriginal, String mimeType) {
        byte[] imageBytes;
        try (InputStream s = seaweedFs.download(s3KeyOriginal)) {
            imageBytes = s.readAllBytes();
        } catch (Exception e) {
            log.warn("Failed to download image {} for vision: {}", s3KeyOriginal, e.getMessage());
            return;
        }
        try {
            VisionClient.VisionResult r = visionClient.describe(imageBytes, mimeType);
            visionBudget.recordCall(r.inputTokens(), r.outputTokens());
            writeService.reviseCell(SYSTEM_PRINCIPAL, cellId, r.description(), null);
            removeTag(cellId, "vision_pending");
            log.debug("Vision description stored for cell {}", cellId);
        } catch (HttpClientErrorException.TooManyRequests e) {
            log.warn("Vision 429 for cell {} — will retry on backfill", cellId);
        } catch (VisionClient.OversizeImageException e) {
            tagFailed(cellId, "vision_failed");
            removeTag(cellId, "vision_pending");
            log.info("Vision skipped (oversize) for cell {}", cellId);
        } catch (IllegalArgumentException e) {
            tagFailed(cellId, "vision_failed");
            removeTag(cellId, "vision_pending");
            log.info("Vision skipped (unsupported mime) for cell {}: {}", cellId, e.getMessage());
        } catch (Exception e) {
            log.warn("Vision describe failed for cell {}: {}", cellId, e.getMessage());
        }
    }

    private void removeTag(UUID cellId, String tag) {
        dsl.execute("UPDATE cells SET tags = array_remove(tags, ?) WHERE id = ?", tag, cellId);
    }

    private void tagFailed(UUID cellId, String tag) {
        dsl.execute(
                "UPDATE cells SET tags = "
                        + "CASE WHEN ? = ANY(tags) THEN tags ELSE array_append(tags, ?) END "
                        + "WHERE id = ?", tag, tag, cellId);
    }
}
