package com.hivemem.attachment;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

@Component
public class PdfAttachmentParser implements AttachmentParser {

    private static final int THUMBNAIL_MAX_WIDTH = 500;
    private static final float RENDER_DPI = 150f;

    @Override
    public boolean supports(String mimeType) {
        return "application/pdf".equals(mimeType);
    }

    @Override
    public ParseResult parse(InputStream content) throws Exception {
        try (PDDocument doc = Loader.loadPDF(content.readAllBytes())) {
            String text = new PDFTextStripper().getText(doc);
            byte[] thumbnail = renderFirstPageThumbnail(doc);
            return ParseResult.withThumbnail(text.isBlank() ? null : text.strip(), thumbnail);
        }
    }

    private byte[] renderFirstPageThumbnail(PDDocument doc) throws Exception {
        PDFRenderer renderer = new PDFRenderer(doc);
        BufferedImage full = renderer.renderImageWithDPI(0, RENDER_DPI);
        BufferedImage scaled = scale(full, THUMBNAIL_MAX_WIDTH);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(scaled, "JPEG", out);
        return out.toByteArray();
    }

    private BufferedImage scale(BufferedImage src, int maxWidth) {
        if (src.getWidth() <= maxWidth) return src;
        int height = (int) ((long) src.getHeight() * maxWidth / src.getWidth());
        BufferedImage dst = new BufferedImage(maxWidth, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, maxWidth, height, null);
        g.dispose();
        return dst;
    }
}
