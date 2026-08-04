package com.hivemem.ocr;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;

/**
 * Decides whether a rasterized page image is "blank" (near-white). Pure + stateless so it is trivially
 * unit-testable and callable from both the reassembly and OCR stages. Fails safe: any decode error or
 * empty image returns false (treat as NOT blank) so a page is never dropped on a technicality.
 */
public final class BlankPageDetector {

    /** A pixel counts as "white" at/above this per-channel luminance (0..255). */
    private static final int WHITE_LUMA = 245;

    private BlankPageDetector() {}

    /**
     * @param pngBytes  PNG-encoded page image
     * @param threshold page is blank when the fraction of near-white pixels is >= this (e.g. 0.995)
     * @return true if the page is near-white (blank); false on any decode failure or if below threshold
     */
    public static boolean isNearWhite(byte[] pngBytes, double threshold) {
        // NaN >= x is false, so an undecodable image is never near-white — the fail-safe above.
        return whiteFraction(pngBytes) >= threshold;
    }

    /**
     * The fraction of near-white pixels in the sampled grid, so a caller that weighs one page
     * against several thresholds decodes it once instead of once per threshold.
     *
     * @param pngBytes PNG-encoded page image
     * @return the fraction in 0..1, or {@code NaN} if the image cannot be read. NaN and not 0.0 on
     *     purpose: every {@code >=} comparison against it is false, so a page is never treated as
     *     blank — and never dropped — because its bytes could not be decoded.
     */
    public static double whiteFraction(byte[] pngBytes) {
        if (pngBytes == null || pngBytes.length == 0) return Double.NaN;
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(pngBytes));
        } catch (Exception e) {
            return Double.NaN;
        }
        if (img == null) return Double.NaN;
        int w = img.getWidth(), h = img.getHeight();
        if (w == 0 || h == 0) return Double.NaN;

        int stepX = Math.max(1, w / 200);
        int stepY = Math.max(1, h / 200);
        long total = 0, white = 0;
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                int luma = (r * 299 + g * 587 + b * 114) / 1000;
                if (luma >= WHITE_LUMA) white++;
                total++;
            }
        }
        return total > 0 ? (double) white / total : Double.NaN;
    }
}
