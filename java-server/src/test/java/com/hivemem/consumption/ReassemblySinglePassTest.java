package com.hivemem.consumption;

import static org.junit.jupiter.api.Assertions.*;

import com.hivemem.ocr.PdfPageRasterizer;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

class ReassemblySinglePassTest {

    /** Synthetic PDF — never taken from the production corpus (CLAUDE.md §1). */
    private static byte[] blankPdf(int pages) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** The streaming overload must hand out one page at a time and never hold two at once. This is
     *  the property that lets the page cap rise: heap use stops scaling with batch size. */
    @Test
    void streamingRasterizerKeepsOnlyOnePageAlive() throws Exception {
        byte[] pdf = blankPdf(12);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger seen = new AtomicInteger();

        new PdfPageRasterizer().rasterize(pdf, 72, 500, (index, png) -> {
            int now = concurrent.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            seen.incrementAndGet();
            assertNotNull(png);
            concurrent.decrementAndGet();
        });

        assertEquals(12, seen.get(), "every page must be delivered");
        assertEquals(1, peak.get(), "at most one page image may be alive at a time");
    }
}
