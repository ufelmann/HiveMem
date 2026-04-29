package com.hivemem.attachment;

public record ParseResult(String extractedText, byte[] thumbnail, String thumbnailMimeType) {

    static final int MAX_TEXT_CHARS = 10_000;
    private static final String TRUNCATION_SUFFIX = "… [truncated]";

    public static ParseResult textOnly(String text) {
        return new ParseResult(truncate(text), null, null);
    }

    public static ParseResult withThumbnail(String text, byte[] thumbnail) {
        return new ParseResult(truncate(text), thumbnail, "image/jpeg");
    }

    public static ParseResult empty() {
        return new ParseResult(null, null, null);
    }

    public boolean hasThumbnail() {
        return thumbnail != null && thumbnail.length > 0;
    }

    public boolean wasTextTruncated() {
        return extractedText != null && extractedText.endsWith(TRUNCATION_SUFFIX);
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= MAX_TEXT_CHARS) return text;
        int cutAt = MAX_TEXT_CHARS;
        if (Character.isLowSurrogate(text.charAt(cutAt)) && cutAt > 0) cutAt--;
        return text.substring(0, cutAt) + TRUNCATION_SUFFIX;
    }
}
