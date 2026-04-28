package com.hivemem.attachment;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

@Component
public class ImageAttachmentParser implements AttachmentParser {

    private static final int THUMBNAIL_MAX_WIDTH = 500;

    @Override
    public boolean supports(String mimeType) {
        return mimeType != null && mimeType.startsWith("image/");
    }

    @Override
    public ParseResult parse(InputStream content) throws Exception {
        BufferedImage src = ImageIO.read(content);
        if (src == null) return ParseResult.empty();
        BufferedImage scaled = scale(src, THUMBNAIL_MAX_WIDTH);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(scaled, "JPEG", out);
        return ParseResult.withThumbnail(null, out.toByteArray());
    }

    private BufferedImage scale(BufferedImage src, int maxWidth) {
        if (src.getWidth() <= maxWidth) {
            BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.drawImage(src, 0, 0, null);
            g.dispose();
            return rgb;
        }
        int height = (int) ((long) src.getHeight() * maxWidth / src.getWidth());
        BufferedImage dst = new BufferedImage(maxWidth, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, maxWidth, height, null);
        g.dispose();
        return dst;
    }
}
