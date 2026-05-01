package com.hivemem.ocr;

public class ScanDetector {

    public boolean isScan(String extractedText, int pageCount, int thresholdCharsPerPage) {
        if (pageCount <= 0) return false;
        int chars = (extractedText == null) ? 0 : extractedText.length();
        return (chars / pageCount) < thresholdCharsPerPage;
    }
}
