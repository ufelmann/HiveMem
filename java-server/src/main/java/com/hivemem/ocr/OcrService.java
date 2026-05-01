package com.hivemem.ocr;

import com.hivemem.attachment.SeaweedFsClient;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.write.WriteToolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "hivemem.ocr.enabled", havingValue = "true")
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);
    private static final AuthPrincipal SYSTEM_PRINCIPAL =
            new AuthPrincipal("system-ocr", AuthRole.ADMIN);

    private final OcrProperties props;
    private final OcrRepository repo;
    private final SeaweedFsClient seaweed;
    private final TesseractRunner tesseract;
    private final PdfPageRasterizer rasterizer;
    private final WriteToolService writeService;

    public OcrService(OcrProperties props,
                      OcrRepository repo,
                      SeaweedFsClient seaweed,
                      WriteToolService writeService) {
        this.props = props;
        this.repo = repo;
        this.seaweed = seaweed;
        this.tesseract = new TesseractRunner(props.getTesseractPath());
        this.rasterizer = new PdfPageRasterizer();
        this.writeService = writeService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOcrRequested(OcrRequestedEvent event) {
        processOne(event.cellId(), event.s3Key());
    }

    @Scheduled(fixedRateString = "${hivemem.ocr.backfill-interval-ms:3600000}")
    public void backfill() {
        List<UUID> ids = repo.findCellsPendingOcr(props.getBackfillBatchSize());
        for (UUID id : ids) {
            var info = repo.findAttachmentForCell(id).orElse(null);
            if (info == null) {
                log.warn("OCR backfill: cell {} has no attachment, skipping", id);
                continue;
            }
            processOne(id, info.s3Key());
        }
    }

    void processOne(UUID cellId, String s3Key) {
        try {
            byte[] pdfBytes;
            try (InputStream in = seaweed.download(s3Key)) {
                pdfBytes = in.readAllBytes();
            }
            List<byte[]> pages = rasterizer.rasterize(pdfBytes, props.getRenderDpi(), props.getMaxPages());

            StringBuilder out = new StringBuilder();
            for (int i = 0; i < pages.size(); i++) {
                String text;
                try {
                    text = tesseract.ocr(pages.get(i), props.getLanguages(), props.getCallTimeoutSeconds());
                } catch (Exception e) {
                    log.warn("OCR page {} of cell {} failed: {}", i + 1, cellId, e.getMessage());
                    text = "[page=" + (i + 1) + ": OCR failed]";
                }
                out.append("[page=").append(i + 1).append("]\n").append(text).append("\n\n");
            }

            // Push the OCR'd text into the cell. reviseCell will recompute the embedding
            // (encodeForCell), and since the new content is long with no summary, the
            // SummarizerService picks it up automatically via needs_summary.
            writeService.reviseCell(SYSTEM_PRINCIPAL, cellId, out.toString().trim(), null);
            repo.removeOcrPendingTag(cellId);
        } catch (Exception e) {
            log.error("OCR failed for cell {}: {}", cellId, e.getMessage(), e);
            repo.tagFailed(cellId);
        }
    }
}
