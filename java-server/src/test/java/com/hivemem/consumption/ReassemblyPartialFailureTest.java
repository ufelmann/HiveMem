package com.hivemem.consumption;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hivemem.attachment.AttachmentService;
import com.hivemem.ocr.PdfPageRasterizer;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

class ReassemblyPartialFailureTest {

    private byte[] inkPng() throws Exception {
        BufferedImage img = new BufferedImage(120, 160, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, 120, 160);
        g.setColor(Color.BLACK); g.fillRect(0, 0, 120, 40);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private byte[] nPagePdf(int n) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < n; i++) doc.addPage(new PDPage(PDRectangle.A4));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private static PageOrienter mockOrienter() {
        PageOrienter orienter = mock(PageOrienter.class);
        when(orienter.orient(anyString(), anyInt(), any()))
                .thenAnswer(inv -> new PageOrienter.PageOrientation(inv.getArgument(1), 0, false, 0.9));
        return orienter;
    }

    private static PageMetadataExtractor mockExtractor() {
        PageMetadataExtractor extractor = mock(PageMetadataExtractor.class);
        when(extractor.extract(anyString(), anyInt(), any()))
                .thenAnswer(inv -> new PageMetadataExtractor.PageMetadata(inv.getArgument(1),
                        "S", null, null, "letter", null, "p", false, false));
        return extractor;
    }

    private static MailingAssembler mockAssembler() {
        MailingAssembler assembler = mock(MailingAssembler.class);
        when(assembler.assemble(anyString(), anyList())).thenReturn(List.of());
        return assembler;
    }

    /** Stubs the streaming overload to hand out {@code pngs} one page at a time, mirroring
     *  {@link PdfPageRasterizer#rasterize(byte[], int, int, PdfPageRasterizer.PageConsumer)}. */
    private static void stubPages(PdfPageRasterizer rasterizer, List<byte[]> pngs) throws Exception {
        doAnswer(inv -> {
            PdfPageRasterizer.PageConsumer consumer = inv.getArgument(3);
            for (int i = 0; i < pngs.size(); i++) consumer.accept(i, pngs.get(i));
            return null;
        }).when(rasterizer).rasterize(any(), anyInt(), anyInt(), any());
    }

    /** When the first sub-doc ingests successfully but the second throws, the whole batch must be
     *  routed to failed/ and moveToProcessed must NEVER be called. */
    @Test
    void partialIngestFailureRoutesWholeToFailed() throws Exception {
        ConsumptionProperties props = new ConsumptionProperties();
        PdfPageRasterizer rasterizer = mock(PdfPageRasterizer.class);
        PageOrienter orienter = mockOrienter();
        PageMetadataExtractor extractor = mockExtractor();
        MailingAssembler assembler = mockAssembler();
        PageReassembler reassembler = mock(PageReassembler.class);
        AttachmentService attachments = mock(AttachmentService.class);
        ConsumptionFileMover mover = mock(ConsumptionFileMover.class);

        // Two pages, both with ink (non-blank)
        byte[] page = inkPng();
        stubPages(rasterizer, List.of(page, page));
        // Two documents: page 1 → doc 1, page 2 → doc 2
        when(reassembler.toDocuments(any(), anyInt())).thenReturn(List.of(
                new PageReassembler.ResultDoc(List.of(1), "committed"),
                new PageReassembler.ResultDoc(List.of(2), "committed")));

        // First call to attachments.ingest succeeds; second throws
        doThrow(new RuntimeException("DB down"))
                .when(attachments).ingest(any(InputStream.class), anyString(), anyString(),
                        any(), any(), any(), any(), anyString(), anyString(), anyString());

        Path stagedPath = Path.of("Scan_two_pages.pdf");
        ReassemblyOrchestrator orch = new ReassemblyOrchestrator(props, rasterizer, orienter, extractor,
                assembler, reassembler, new BatchSplitter(), attachments, mover);
        orch.reassemble(stagedPath, nPagePdf(2), 2);

        verify(mover).moveToFailed(stagedPath);
        verify(mover, never()).moveToProcessed(any());
    }

    /** FIX 4: when reassembly throws (forcing degrade path) AND the degrade ingest also throws,
     *  the file must be routed to failed/ and moveToProcessed must NEVER be called. */
    @Test
    void degradeIngestFailureRoutesToFailed() throws Exception {
        ConsumptionProperties props = new ConsumptionProperties();
        PdfPageRasterizer rasterizer = mock(PdfPageRasterizer.class);
        PageOrienter orienter = mockOrienter();
        PageMetadataExtractor extractor = mockExtractor();
        MailingAssembler assembler = mockAssembler();
        PageReassembler reassembler = mock(PageReassembler.class);
        AttachmentService attachments = mock(AttachmentService.class);
        ConsumptionFileMover mover = mock(ConsumptionFileMover.class);
        ConsumptionFileRepository fileRepo = mock(ConsumptionFileRepository.class);

        // Make rasterizer throw to force the degrade path
        doThrow(new RuntimeException("rasterizer crashed"))
                .when(rasterizer).rasterize(any(), anyInt(), anyInt(), any());

        // Make the degrade attachments.ingest also throw
        doThrow(new RuntimeException("S3 down"))
                .when(attachments).ingest(any(InputStream.class), anyString(), anyString(),
                        any(), any(), any(), any(), anyString(), anyString(), anyString());

        Path stagedPath = Path.of("Scan_degrade_fail.pdf");
        ReassemblyOrchestrator orch = new ReassemblyOrchestrator(props, rasterizer, orienter, extractor,
                assembler, reassembler, new BatchSplitter(), attachments, mover);
        orch.reassemble(stagedPath, nPagePdf(2), 2, "deadbeef", fileRepo);

        verify(mover).moveToFailed(stagedPath);
        verify(mover, never()).moveToProcessed(any());
        verify(fileRepo).markFailed(eq("deadbeef"), anyString());
        verify(fileRepo, never()).markDone(any());
    }

    /** M8: the recovery-sweep heartbeat must fire INSIDE the single per-page streaming loop
     *  (orientation + extraction merged into one pass, see collapse in ReassemblyOrchestrator),
     *  not just once at the end, so a large batch's per-page latency can't trip the stale window. */
    @Test
    void heartbeatTouchesLedgerPerPageDuringPasses() throws Exception {
        ConsumptionProperties props = new ConsumptionProperties();
        PdfPageRasterizer rasterizer = mock(PdfPageRasterizer.class);
        PageOrienter orienter = mockOrienter();
        PageMetadataExtractor extractor = mockExtractor();
        MailingAssembler assembler = mockAssembler();
        PageReassembler reassembler = mock(PageReassembler.class);
        AttachmentService attachments = mock(AttachmentService.class);
        ConsumptionFileMover mover = mock(ConsumptionFileMover.class);
        ConsumptionFileRepository fileRepo = mock(ConsumptionFileRepository.class);

        byte[] page = inkPng();
        stubPages(rasterizer, List.of(page, page));
        when(reassembler.toDocuments(any(), anyInt())).thenReturn(List.of(
                new PageReassembler.ResultDoc(List.of(1), "committed"),
                new PageReassembler.ResultDoc(List.of(2), "committed")));

        Path stagedPath = Path.of("Scan_heartbeat.pdf");
        ReassemblyOrchestrator orch = new ReassemblyOrchestrator(props, rasterizer, orienter, extractor,
                assembler, reassembler, new BatchSplitter(), attachments, mover);
        orch.reassemble(stagedPath, nPagePdf(2), 2, "cafebabe", fileRepo);

        // 2 pages, one heartbeat per page in the single streaming pass = at least 2 heartbeats
        verify(fileRepo, atLeast(2)).touch("cafebabe");
        verify(fileRepo).markDone("cafebabe");
    }

    /** The degraded-page count computed in the streaming pass must reach the ledger: a later
     *  review queue is built on total_pages/degraded_pages, so a wrong count here would silently
     *  under- or over-report. */
    @Test
    void recordsPageStatsWithTheDegradedCount() throws Exception {
        ConsumptionProperties props = new ConsumptionProperties();
        PdfPageRasterizer rasterizer = mock(PdfPageRasterizer.class);
        PageOrienter orienter = mockOrienter();
        PageMetadataExtractor extractor = mock(PageMetadataExtractor.class);
        // Page 1 extracts fine; page 2 lost its vision metadata.
        when(extractor.extract(anyString(), anyInt(), any())).thenAnswer(inv -> {
            int page = inv.getArgument(1);
            boolean degraded = page == 2;
            return new PageMetadataExtractor.PageMetadata(page, degraded ? null : "S", null, null,
                    degraded ? null : "letter", null, degraded ? null : "p", false, degraded);
        });
        MailingAssembler assembler = mockAssembler();
        PageReassembler reassembler = mock(PageReassembler.class);
        AttachmentService attachments = mock(AttachmentService.class);
        ConsumptionFileMover mover = mock(ConsumptionFileMover.class);
        ConsumptionFileRepository fileRepo = mock(ConsumptionFileRepository.class);

        byte[] page = inkPng();
        stubPages(rasterizer, List.of(page, page));
        when(reassembler.toDocuments(any(), anyInt())).thenReturn(List.of(
                new PageReassembler.ResultDoc(List.of(1), "committed"),
                new PageReassembler.ResultDoc(List.of(2), "committed")));

        Path stagedPath = Path.of("Scan_degraded_count.pdf");
        ReassemblyOrchestrator orch = new ReassemblyOrchestrator(props, rasterizer, orienter, extractor,
                assembler, reassembler, new BatchSplitter(), attachments, mover);
        orch.reassemble(stagedPath, nPagePdf(2), 2, "cafebabe", fileRepo);

        verify(fileRepo).recordPageStats("cafebabe", 2, 1, 0);
    }

    /** blank_pages must reach the ledger too: a batch that silently loses half its pages to the
     *  blank-page filter needs to be as visible as one that lost pages to a degraded extraction.
     *  One page is voted blank by the extractor (no pixel skip involved here — Task 4 territory),
     *  so {@code blank.size()} must be 1 and degraded must stay 0. */
    @Test
    void recordsPageStatsWithTheBlankCount() throws Exception {
        ConsumptionProperties props = new ConsumptionProperties();
        PdfPageRasterizer rasterizer = mock(PdfPageRasterizer.class);
        PageOrienter orienter = mockOrienter();
        PageMetadataExtractor extractor = mock(PageMetadataExtractor.class);
        // Page 1 is ordinary content; page 2 is voted blank by the extractor.
        when(extractor.extract(anyString(), anyInt(), any())).thenAnswer(inv -> {
            int page = inv.getArgument(1);
            boolean blank = page == 2;
            return new PageMetadataExtractor.PageMetadata(page, "S", null, null,
                    blank ? "blank" : "letter", null, blank ? "blank page" : "p", blank, false);
        });
        MailingAssembler assembler = mockAssembler();
        PageReassembler reassembler = mock(PageReassembler.class);
        AttachmentService attachments = mock(AttachmentService.class);
        ConsumptionFileMover mover = mock(ConsumptionFileMover.class);
        ConsumptionFileRepository fileRepo = mock(ConsumptionFileRepository.class);

        byte[] page = inkPng();
        stubPages(rasterizer, List.of(page, page));
        when(reassembler.toDocuments(any(), anyInt())).thenReturn(List.of(
                new PageReassembler.ResultDoc(List.of(1), "committed"),
                new PageReassembler.ResultDoc(List.of(2), "committed")));

        Path stagedPath = Path.of("Scan_blank_count.pdf");
        ReassemblyOrchestrator orch = new ReassemblyOrchestrator(props, rasterizer, orienter, extractor,
                assembler, reassembler, new BatchSplitter(), attachments, mover);
        orch.reassemble(stagedPath, nPagePdf(2), 2, "cafebabe", fileRepo);

        verify(fileRepo).recordPageStats("cafebabe", 2, 0, 1);
    }

    /** When both sub-docs ingest successfully, the batch must go to processed/ (regression guard). */
    @Test
    void allSuccessfulIngestsRoutesToProcessed() throws Exception {
        ConsumptionProperties props = new ConsumptionProperties();
        PdfPageRasterizer rasterizer = mock(PdfPageRasterizer.class);
        PageOrienter orienter = mockOrienter();
        PageMetadataExtractor extractor = mockExtractor();
        MailingAssembler assembler = mockAssembler();
        PageReassembler reassembler = mock(PageReassembler.class);
        AttachmentService attachments = mock(AttachmentService.class);
        ConsumptionFileMover mover = mock(ConsumptionFileMover.class);

        byte[] page = inkPng();
        stubPages(rasterizer, List.of(page, page));
        when(reassembler.toDocuments(any(), anyInt())).thenReturn(List.of(
                new PageReassembler.ResultDoc(List.of(1), "committed"),
                new PageReassembler.ResultDoc(List.of(2), "committed")));

        Path stagedPath = Path.of("Scan_two_ok.pdf");
        ReassemblyOrchestrator orch = new ReassemblyOrchestrator(props, rasterizer, orienter, extractor,
                assembler, reassembler, new BatchSplitter(), attachments, mover);
        orch.reassemble(stagedPath, nPagePdf(2), 2);

        verify(mover).moveToProcessed(stagedPath);
        verify(mover, never()).moveToFailed(any());
    }
}
