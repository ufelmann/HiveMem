package com.hivemem.consumption;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hivemem.attachment.AttachmentService;
import com.hivemem.ocr.BlankPageDetector;
import com.hivemem.ocr.PdfPageRasterizer;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pixel pre-check in front of the two vision calls: an unambiguously blank page skips both, a page
 * that carries any real ink keeps them. All page images are generated here — never a scanned page.
 */
class ReassemblyBlankSkipTest {

    /** A4 at the production render DPI (150), so the detector's 200x200 sampling grid behaves as it
     *  does in production rather than on a toy-sized image. */
    private static final int W = 1240;
    private static final int H = 1754;

    private static byte[] png(BufferedImage img) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    /** A completely white 150-DPI page — the blank backside of a duplex sheet. */
    private static byte[] blankPage() throws Exception {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.dispose();
        return png(img);
    }

    /**
     * A near-threshold content page: one mark in the top-left corner, nothing else. Its white
     * fraction lands just under the skip threshold, and above the whitest page of the measured
     * content sample — i.e. the sparsest realistic content page, the one a too-eager pre-check
     * would delete without ever asking the model.
     */
    private static byte[] nearThresholdContentPage() throws Exception {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, 190, 400);
        g.dispose();
        return png(img);
    }

    private static byte[] nPagePdf(int n) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < n; i++) doc.addPage(new PDPage(PDRectangle.A4));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private static void stubPages(PdfPageRasterizer rasterizer, List<byte[]> pngs) throws Exception {
        doAnswer(inv -> {
            PdfPageRasterizer.PageConsumer consumer = inv.getArgument(3);
            for (int i = 0; i < pngs.size(); i++) consumer.accept(i, pngs.get(i));
            return null;
        }).when(rasterizer).rasterize(any(), anyInt(), anyInt(), any());
    }

    private static DocGroup group(String id, double confidence, int... pages) {
        DocGroup g = new DocGroup(id, null);
        g.minConfidence = confidence;
        for (int p : pages) g.pages.add(p);
        return g;
    }

    /** Everything the orchestrator needs, all mocked; the splitter is real so the emitted PDF is real. */
    private record Rig(ConsumptionProperties props, PdfPageRasterizer rasterizer, PageOrienter orienter,
                       PageMetadataExtractor extractor, MailingAssembler assembler,
                       PageReassembler reassembler, AttachmentService attachments,
                       ConsumptionFileMover mover, ConsumptionFileRepository fileRepo) {

        static Rig create() {
            Rig r = new Rig(new ConsumptionProperties(), mock(PdfPageRasterizer.class),
                    mock(PageOrienter.class), mock(PageMetadataExtractor.class),
                    mock(MailingAssembler.class), mock(PageReassembler.class),
                    mock(AttachmentService.class), mock(ConsumptionFileMover.class),
                    mock(ConsumptionFileRepository.class));
            when(r.orienter().orient(anyString(), anyInt(), any()))
                    .thenAnswer(inv -> new PageOrienter.PageOrientation(inv.getArgument(1), 0, false, 0.9));
            when(r.extractor().extract(anyString(), anyInt(), any()))
                    .thenAnswer(inv -> new PageMetadataExtractor.PageMetadata(inv.getArgument(1),
                            "SYNTHETIC SENDER", null, null, "letter", null, "p", false, false));
            return r;
        }

        ReassemblyOrchestrator orchestrator() {
            return new ReassemblyOrchestrator(props, rasterizer, orienter, extractor, assembler,
                    reassembler, new BatchSplitter(), attachments, mover);
        }
    }

    @Test
    void nearThresholdContentPageStaysBelowTheSkipThreshold() throws Exception {
        ConsumptionProperties props = new ConsumptionProperties();
        Assertions.assertFalse(
                BlankPageDetector.isNearWhite(nearThresholdContentPage(), props.getBlankSkipWhiteFraction()),
                "a page carrying a single corner mark must not read as pixel-blank");
        Assertions.assertTrue(BlankPageDetector.isNearWhite(blankPage(), props.getBlankSkipWhiteFraction()),
                "a fully white page must read as pixel-blank");
    }

    @Test
    void nearThresholdContentPageKeepsBothVisionCallsAndSurvivesIngest() throws Exception {
        Rig r = Rig.create();
        when(r.assembler().assemble(anyString(), anyList())).thenReturn(List.of(group("d", 0.9, 1)));
        stubPages(r.rasterizer(), List.of(nearThresholdContentPage()));
        when(r.reassembler().toDocuments(any(), eq(1)))
                .thenReturn(List.of(new PageReassembler.ResultDoc(List.of(1), "committed")));

        r.orchestrator().reassemble(Path.of("Scan_near_threshold.pdf"), nPagePdf(1), 1);

        // (b) both vision calls still happen
        verify(r.orienter(), times(1)).orient(anyString(), eq(1), any());
        verify(r.extractor(), times(1)).extract(anyString(), eq(1), any());
        // (a) + (c) the page is not dropped as blank — it is in the ingested PDF
        ArgumentCaptor<InputStream> pdf = ArgumentCaptor.forClass(InputStream.class);
        verify(r.attachments(), times(1)).ingest(pdf.capture(), anyString(), eq("application/pdf"),
                any(), any(), any(), any(), eq("consumption"), anyString(), eq("consumption:"));
        try (PDDocument ingested = Loader.loadPDF(pdf.getValue().readAllBytes())) {
            Assertions.assertEquals(1, ingested.getNumberOfPages(),
                    "a near-threshold content page must survive into the ingested document");
        }
    }

    @Test
    void pixelBlankPageSkipsBothCallsAndKeepsItsPageNumberInMeta() throws Exception {
        Rig r = Rig.create();
        when(r.assembler().assemble(anyString(), anyList())).thenReturn(List.of(group("d", 0.9, 1, 2)));
        stubPages(r.rasterizer(), List.of(nearThresholdContentPage(), blankPage()));
        when(r.reassembler().toDocuments(any(), eq(2)))
                .thenReturn(List.of(new PageReassembler.ResultDoc(List.of(1, 2), "committed")));

        r.orchestrator().reassemble(Path.of("Scan_duplex.pdf"), nPagePdf(2), 2, "h", r.fileRepo());

        // No vision call for the blank backside, both for the content page.
        verify(r.orienter(), never()).orient(anyString(), eq(2), any());
        verify(r.extractor(), never()).extract(anyString(), eq(2), any());
        verify(r.orienter(), times(1)).orient(anyString(), eq(1), any());
        verify(r.extractor(), times(1)).extract(anyString(), eq(1), any());

        // The mirror: the blank page still appears, with its own page number, in the meta list that
        // drives (n, n+1) sheet pairing.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PageMetadataExtractor.PageMetadata>> metaCap =
                ArgumentCaptor.forClass(List.class);
        verify(r.assembler()).assemble(anyString(), metaCap.capture());
        List<PageMetadataExtractor.PageMetadata> meta = metaCap.getValue();
        Assertions.assertEquals(2, meta.size(), "every page must contribute one meta entry");
        Assertions.assertEquals(1, meta.get(0).page());
        PageMetadataExtractor.PageMetadata blank = meta.get(1);
        Assertions.assertEquals(2, blank.page(), "the pixel-skipped page keeps its page number");
        Assertions.assertTrue(blank.blank());
        Assertions.assertFalse(blank.degraded(), "a pixel-blank page is not a degradation");
        Assertions.assertEquals("blank", blank.docType());
        Assertions.assertEquals("blank page (pixel-detected)", blank.summary());
        Assertions.assertNull(blank.sender());
        Assertions.assertNull(blank.date());
        Assertions.assertNull(blank.pageLabel());
        Assertions.assertNull(blank.reference());

        // The blank page is counted as blank, and degraded stays at zero.
        verify(r.fileRepo()).recordPageStats("h", 2, 0, 1);

        // It is a delete list: the emitted PDF holds the content page only.
        ArgumentCaptor<InputStream> pdf = ArgumentCaptor.forClass(InputStream.class);
        verify(r.attachments(), times(1)).ingest(pdf.capture(), anyString(), eq("application/pdf"),
                any(), any(), any(), any(), eq("consumption"), anyString(), eq("consumption:"));
        try (PDDocument ingested = Loader.loadPDF(pdf.getValue().readAllBytes())) {
            Assertions.assertEquals(1, ingested.getNumberOfPages());
        }
    }

    @Test
    void killSwitchDisablesThePreSkipToo() throws Exception {
        Rig r = Rig.create();
        r.props().setBlankFilterEnabled(false);
        when(r.assembler().assemble(anyString(), anyList())).thenReturn(List.of(group("d", 0.9, 1)));
        stubPages(r.rasterizer(), List.of(blankPage()));
        when(r.reassembler().toDocuments(any(), eq(1)))
                .thenReturn(List.of(new PageReassembler.ResultDoc(List.of(1), "committed")));

        r.orchestrator().reassemble(Path.of("Scan_switch_off.pdf"), nPagePdf(1), 1);

        verify(r.orienter(), times(1)).orient(anyString(), eq(1), any());
        verify(r.extractor(), times(1)).extract(anyString(), eq(1), any());
    }
}
