package com.hivemem.ocr;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScanDetectorTest {

    private final ScanDetector detector = new ScanDetector();

    @Test
    void detectsScan_whenAvgCharsPerPageBelowThreshold() {
        assertTrue(detector.isScan("a".repeat(100), 5, 50));
    }

    @Test
    void notScan_whenAvgCharsPerPageMeetsThreshold() {
        assertFalse(detector.isScan("a".repeat(250), 5, 50));
    }

    @Test
    void notScan_whenLongText() {
        assertFalse(detector.isScan("a".repeat(10_000), 5, 50));
    }

    @Test
    void detectsScan_whenEmptyText() {
        assertTrue(detector.isScan("", 3, 50));
    }

    @Test
    void notScan_whenZeroPages() {
        assertFalse(detector.isScan("anything", 0, 50));
    }

    @Test
    void notScan_whenTextNullAndZeroPages() {
        assertFalse(detector.isScan(null, 0, 50));
    }

    @Test
    void detectsScan_whenTextNullAndPagesPositive() {
        assertTrue(detector.isScan(null, 5, 50));
    }
}
