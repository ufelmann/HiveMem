package com.hivemem.attachment;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class PdfAttachmentParserTest {

    private final PdfAttachmentParser parser = new PdfAttachmentParser();

    @Test
    void supportsPdf() {
        assertThat(parser.supports("application/pdf")).isTrue();
        assertThat(parser.supports("image/png")).isFalse();
    }

    @Test
    void extractsTextAndGeneratesThumbnail() throws Exception {
        byte[] pdfBytes = buildSinglePagePdf("Hello from HiveMem");
        ParseResult result = parser.parse(new ByteArrayInputStream(pdfBytes));

        assertThat(result.extractedText()).contains("Hello from HiveMem");
        assertThat(result.hasThumbnail()).isTrue();
        assertThat(result.thumbnailMimeType()).isEqualTo("image/jpeg");
    }

    @Test
    void extractsLongTextWithoutTruncation() throws Exception {
        // Build a PDF with > 10 000 chars of text
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 11_000) sb.append("Lorem ipsum dolor sit amet. ");
        byte[] pdf = buildSinglePagePdf(sb.toString());

        ParseResult result;
        try (InputStream in = new ByteArrayInputStream(pdf)) {
            result = parser.parse(in);
        }

        assertThat(result.extractedText()).isNotNull();
        // Verify that text is not truncated - should contain full content
        assertThat(result.extractedText().length()).isGreaterThan(10_000);
    }

    private byte[] buildSinglePagePdf(String text) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
