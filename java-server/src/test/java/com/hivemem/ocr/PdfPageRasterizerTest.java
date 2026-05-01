package com.hivemem.ocr;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfPageRasterizerTest {

    @Test
    void rasterizesEachPageToPng() throws Exception {
        ByteArrayOutputStream pdfBytes = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < 3; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    // empty page
                }
            }
            doc.save(pdfBytes);
        }

        PdfPageRasterizer rasterizer = new PdfPageRasterizer();
        List<byte[]> pages = rasterizer.rasterize(pdfBytes.toByteArray(), 100, 50);

        assertEquals(3, pages.size());
        for (byte[] png : pages) {
            assertTrue(png.length > 0);
            assertEquals((byte) 0x89, png[0]);
            assertEquals((byte) 0x50, png[1]);
        }
    }

    @Test
    void respectsMaxPages() throws Exception {
        ByteArrayOutputStream pdfBytes = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < 10; i++) doc.addPage(new PDPage());
            doc.save(pdfBytes);
        }

        PdfPageRasterizer rasterizer = new PdfPageRasterizer();
        List<byte[]> pages = rasterizer.rasterize(pdfBytes.toByteArray(), 72, 3);
        assertEquals(3, pages.size());
    }
}
