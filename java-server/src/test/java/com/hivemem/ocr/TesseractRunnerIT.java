package com.hivemem.ocr;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class TesseractRunnerIT {

    @Test
    void recognizesPlainText() throws Exception {
        BufferedImage img = new BufferedImage(800, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 800, 200);
        g.setColor(Color.BLACK);
        g.setFont(new Font("SansSerif", Font.BOLD, 64));
        g.drawString("HELLO HIVEMEM", 50, 120);
        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);

        TesseractRunner runner = new TesseractRunner("tesseract");
        String text = runner.ocr(baos.toByteArray(), "eng", 30);

        assertNotNull(text);
        assertTrue(text.toUpperCase().contains("HELLO"),
                "Expected 'HELLO' in OCR output, got: " + text);
        assertTrue(text.toUpperCase().contains("HIVEMEM"),
                "Expected 'HIVEMEM' in OCR output, got: " + text);
    }
}
