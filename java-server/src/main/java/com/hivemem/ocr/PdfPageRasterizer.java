package com.hivemem.ocr;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class PdfPageRasterizer {

    /**
     * Renders each page (up to {@code maxPages}) at {@code dpi} and hands it to
     * {@code consumer} one page at a time, discarding the rendered bytes before moving to the
     * next page. This keeps at most one rendered page alive at a time, so heap use does not
     * scale with batch size. There is deliberately no overload that renders every page up front
     * and returns them as a list — a large batch (up to 50 @ 300 DPI) held entirely in memory
     * risks exhausting the heap, and every consumer (OCR, consumption's batch reassembly) can be
     * expressed as a single streaming pass over this callback.
     */
    public void rasterize(byte[] pdfBytes, int dpi, int maxPages, PageConsumer consumer) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pageCount = Math.min(doc.getNumberOfPages(), maxPages);
            for (int i = 0; i < pageCount; i++) {
                BufferedImage img = renderer.renderImageWithDPI(i, dpi);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(img, "png", baos);
                consumer.accept(i, baos.toByteArray());
            }
        }
    }

    /** Receives one rendered page at a time; {@code pageIndex} is 0-based. */
    @FunctionalInterface
    public interface PageConsumer {
        void accept(int pageIndex, byte[] pngBytes) throws IOException;
    }
}
