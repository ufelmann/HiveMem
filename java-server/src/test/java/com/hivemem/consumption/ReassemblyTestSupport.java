package com.hivemem.consumption;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;

import com.hivemem.ocr.PdfPageRasterizer;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

/** Fixtures shared by the {@code Reassembly*} orchestrator tests. Everything here is generated in
 *  code — no fixture in this package may come from a scanned document. */
final class ReassemblyTestSupport {

    private ReassemblyTestSupport() {}

    /** An n-page A4 PDF with no content: the orchestrator tests stub the rasterizer, so only the
     *  page count of the input matters. */
    static byte[] nPagePdf(int n) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < n; i++) doc.addPage(new PDPage(PDRectangle.A4));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    /** Stubs the streaming overload to hand out {@code pngs} one page at a time, mirroring
     *  {@link PdfPageRasterizer#rasterize(byte[], int, int, PdfPageRasterizer.PageConsumer)}. */
    static void stubPages(PdfPageRasterizer rasterizer, List<byte[]> pngs) throws Exception {
        doAnswer(inv -> {
            PdfPageRasterizer.PageConsumer consumer = inv.getArgument(3);
            for (int i = 0; i < pngs.size(); i++) consumer.accept(i, pngs.get(i));
            return null;
        }).when(rasterizer).rasterize(any(), anyInt(), anyInt(), any());
    }

    static DocGroup group(String id, double confidence, int... pages) {
        DocGroup g = new DocGroup(id, null);
        g.minConfidence = confidence;
        for (int p : pages) g.pages.add(p);
        return g;
    }
}
