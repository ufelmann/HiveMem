package com.hivemem.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class BlankPageDetectorTest {

    private byte[] png(java.util.function.Consumer<Graphics2D> paint) throws Exception {
        BufferedImage img = new BufferedImage(200, 280, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 200, 280);
        paint.accept(g);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    @Test
    void allWhiteIsBlank() throws Exception {
        byte[] white = png(g -> {});
        assertTrue(BlankPageDetector.isNearWhite(white, 0.995));
    }

    @Test
    void pageWithTextBlockIsNotBlank() throws Exception {
        byte[] withInk = png(g -> { g.setColor(Color.BLACK); g.fillRect(0, 0, 200, 28); });
        assertFalse(BlankPageDetector.isNearWhite(withInk, 0.995));
    }

    @Test
    void faintSpeckSurvivesConservativeThreshold() throws Exception {
        byte[] speck = png(g -> { g.setColor(Color.BLACK); g.fillRect(0, 0, 4, 4); });
        assertTrue(BlankPageDetector.isNearWhite(speck, 0.995));
    }

    @Test
    void undecodableBytesAreNotBlank() {
        assertFalse(BlankPageDetector.isNearWhite(new byte[] {1, 2, 3}, 0.995));
    }

    /** Callers that test one page against two thresholds decode it once and compare the fraction
     *  themselves, so the fraction has to be reachable and has to agree with {@code isNearWhite}. */
    @Test
    void whiteFractionAgreesWithIsNearWhite() throws Exception {
        byte[] withInk = png(g -> { g.setColor(Color.BLACK); g.fillRect(0, 0, 200, 28); });
        double f = BlankPageDetector.whiteFraction(withInk);
        assertTrue(f > 0.85 && f < 0.95, "one tenth of the page inked, got " + f);
        assertEquals(f >= 0.995, BlankPageDetector.isNearWhite(withInk, 0.995));
        assertEquals(f >= 0.90, BlankPageDetector.isNearWhite(withInk, 0.90));
        assertEquals(1.0, BlankPageDetector.whiteFraction(png(g -> {})), 1e-9);
    }

    /** NaN, not 0.0: an undecodable image must fail every {@code >=} comparison a caller makes, so a
     *  page is never dropped because its bytes could not be read. */
    @Test
    void undecodableBytesHaveNoWhiteFraction() {
        assertTrue(Double.isNaN(BlankPageDetector.whiteFraction(new byte[] {1, 2, 3})));
        assertTrue(Double.isNaN(BlankPageDetector.whiteFraction(null)));
        assertTrue(Double.isNaN(BlankPageDetector.whiteFraction(new byte[0])));
        assertFalse(BlankPageDetector.whiteFraction(new byte[] {1, 2, 3}) >= 0.0);
    }
}
