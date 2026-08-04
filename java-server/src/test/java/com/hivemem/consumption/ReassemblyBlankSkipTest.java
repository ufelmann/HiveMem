package com.hivemem.consumption;

import static com.hivemem.consumption.ReassemblyTestSupport.group;
import static com.hivemem.consumption.ReassemblyTestSupport.nPagePdf;
import static com.hivemem.consumption.ReassemblyTestSupport.stubPages;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pixel pre-check in front of the vision calls: a near-white page skips the orientation call but
 * still gets the metadata call, whose verdict is what may delete it. All page images are generated
 * here — never a scanned page.
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

    /**
     * The page the whole design turns on: a small stamp in one corner (~1 % ink) on an otherwise
     * empty A4 sheet. It reads as pixel-blank — its white fraction sits above the skip threshold but
     * below the 0.995 post-check — so only the model's vote can keep it out of the delete list.
     */
    private static byte[] stampOnlyPage() throws Exception {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, 120, 190);
        g.dispose();
        return png(img);
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
            // The extractor is the deciding vote: by default it agrees with the pixel finding it was
            // handed, which is what the model does on a real white backside.
            when(r.extractor().extract(anyString(), anyInt(), any(), anyBoolean()))
                    .thenAnswer(inv -> {
                        boolean pixelBlank = inv.getArgument(3);
                        return pixelBlank
                                ? new PageMetadataExtractor.PageMetadata(inv.getArgument(1), null, null,
                                        null, "blank", null, "Blank back side.", true, false)
                                : new PageMetadataExtractor.PageMetadata(inv.getArgument(1),
                                        "SYNTHETIC SENDER", null, null, "letter", null, "p", false, false);
                    });
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

        // (b) both vision calls still happen, and the extractor is told the page is not pixel-blank
        verify(r.orienter(), times(1)).orient(anyString(), eq(1), any());
        verify(r.extractor(), times(1)).extract(anyString(), eq(1), any(), eq(false));
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
    void pixelBlankPageSkipsOrientButStillGetsTheExtractCall() throws Exception {
        Rig r = Rig.create();
        when(r.assembler().assemble(anyString(), anyList())).thenReturn(List.of(group("d", 0.9, 1, 2)));
        stubPages(r.rasterizer(), List.of(nearThresholdContentPage(), blankPage()));
        when(r.reassembler().toDocuments(any(), eq(2)))
                .thenReturn(List.of(new PageReassembler.ResultDoc(List.of(1, 2), "committed")));

        r.orchestrator().reassemble(Path.of("Scan_duplex.pdf"), nPagePdf(2), 2, "h", r.fileRepo());

        // A white page has no orientation, so that call is dropped — but the extractor still gets it,
        // with the pixel finding, because its blank vote is what actually deletes the page.
        verify(r.orienter(), never()).orient(anyString(), eq(2), any());
        verify(r.extractor(), times(1)).extract(anyString(), eq(2), any(), eq(true));
        verify(r.orienter(), times(1)).orient(anyString(), eq(1), any());
        verify(r.extractor(), times(1)).extract(anyString(), eq(1), any(), eq(false));

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
        Assertions.assertEquals(2, blank.page(), "the blank page keeps its page number");
        Assertions.assertTrue(blank.blank());
        Assertions.assertFalse(blank.degraded(), "a blank page is not a degradation");

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

    /** The reason both calls are no longer skipped: a stamp-only page reads as pixel-blank, and if
     *  the pixel finding were the sole authority it would be deleted without a trace. The model's
     *  non-blank vote must keep it in the archive. */
    @Test
    void stampOnlyPageSurvivesWhenTheModelVotesNonBlank() throws Exception {
        Rig r = Rig.create();
        ConsumptionProperties props = new ConsumptionProperties();
        Assertions.assertTrue(BlankPageDetector.isNearWhite(stampOnlyPage(), props.getBlankSkipWhiteFraction()),
                "the stamp-only page must be pixel-blank, or this test proves nothing");
        Assertions.assertFalse(BlankPageDetector.isNearWhite(stampOnlyPage(), props.getBlankWhiteFraction()),
                "…and must stay below the 0.995 post-check, which would drop it regardless of the vote");

        // The model reads the stamp and says: not blank.
        when(r.extractor().extract(anyString(), anyInt(), any(), anyBoolean()))
                .thenAnswer(inv -> new PageMetadataExtractor.PageMetadata(inv.getArgument(1),
                        "SYNTHETIC INSURER", null, null, "receipt", null, "A stamped receipt.",
                        false, false));
        when(r.assembler().assemble(anyString(), anyList())).thenReturn(List.of(group("d", 0.9, 1)));
        stubPages(r.rasterizer(), List.of(stampOnlyPage()));
        when(r.reassembler().toDocuments(any(), eq(1)))
                .thenReturn(List.of(new PageReassembler.ResultDoc(List.of(1), "committed")));

        r.orchestrator().reassemble(Path.of("Scan_stamp.pdf"), nPagePdf(1), 1);

        verify(r.orienter(), never()).orient(anyString(), anyInt(), any());
        verify(r.extractor(), times(1)).extract(anyString(), eq(1), any(), eq(true));
        ArgumentCaptor<InputStream> pdf = ArgumentCaptor.forClass(InputStream.class);
        verify(r.attachments(), times(1)).ingest(pdf.capture(), anyString(), eq("application/pdf"),
                any(), any(), any(), any(), eq("consumption"), anyString(), eq("consumption:"));
        try (PDDocument ingested = Loader.loadPDF(pdf.getValue().readAllBytes())) {
            Assertions.assertEquals(1, ingested.getNumberOfPages(),
                    "a stamped page must never be deleted on a pixel judgement alone");
        }
    }

    /** The existing 0.995 post-check is untouched and still the last word on a fully white page,
     *  even when the model votes non-blank. */
    @Test
    void postCheckStillDropsAFullyWhitePageWhenTheModelVotesNonBlank() throws Exception {
        Rig r = Rig.create();
        when(r.extractor().extract(anyString(), anyInt(), any(), anyBoolean()))
                .thenAnswer(inv -> new PageMetadataExtractor.PageMetadata(inv.getArgument(1),
                        "SYNTHETIC SENDER", null, null, "letter", null, "p", false, false));
        when(r.assembler().assemble(anyString(), anyList())).thenReturn(List.of(group("d", 0.9, 1, 2)));
        stubPages(r.rasterizer(), List.of(nearThresholdContentPage(), blankPage()));
        when(r.reassembler().toDocuments(any(), eq(2)))
                .thenReturn(List.of(new PageReassembler.ResultDoc(List.of(1, 2), "committed")));

        r.orchestrator().reassemble(Path.of("Scan_postcheck.pdf"), nPagePdf(2), 2);

        ArgumentCaptor<InputStream> pdf = ArgumentCaptor.forClass(InputStream.class);
        verify(r.attachments(), times(1)).ingest(pdf.capture(), anyString(), eq("application/pdf"),
                any(), any(), any(), any(), eq("consumption"), anyString(), eq("consumption:"));
        try (PDDocument ingested = Loader.loadPDF(pdf.getValue().readAllBytes())) {
            Assertions.assertEquals(1, ingested.getNumberOfPages(),
                    "the 0.995 post-check must still drop a completely white page");
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
        verify(r.extractor(), times(1)).extract(anyString(), eq(1), any(), eq(false));
    }
}
