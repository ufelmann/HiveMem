package com.hivemem.ocr;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PdfPageRasterizer {

    public List<byte[]> rasterize(byte[] pdfBytes, int dpi, int maxPages) throws IOException {
        List<byte[]> out = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pageCount = Math.min(doc.getNumberOfPages(), maxPages);
            for (int i = 0; i < pageCount; i++) {
                BufferedImage img = renderer.renderImageWithDPI(i, dpi);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(img, "png", baos);
                out.add(baos.toByteArray());
            }
        }
        return out;
    }
}
