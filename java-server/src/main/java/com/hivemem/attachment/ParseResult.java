package com.hivemem.attachment;

public record ParseResult(String extractedText, boolean textTruncated, byte[] thumbnail, String thumbnailMimeType) {

    static final int MAX_TEXT_CHARS = 10_000;
    private static final String TRUNCATION_SUFFIX = "… [truncated]";

    public static ParseResult textOnly(String text) {
        String t = truncateIfNeeded(text);
        return new ParseResult(t, t != null && t != text, null, null);
    }

    public static ParseResult withThumbnail(String text, byte[] thumbnail) {
        String t = truncateIfNeeded(text);
        return new ParseResult(t, t != null && t != text, thumbnail, "image/jpeg");
    }

    public static ParseResult empty() {
        return new ParseResult(null, false, null, null);
    }

    public boolean hasThumbnail() {
        return thumbnail != null && thumbnail.length > 0;
    }

    public boolean wasTextTruncated() {
        return textTruncated;
    }

    private static String truncateIfNeeded(String text) {
        if (text == null || text.length() <= MAX_TEXT_CHARS) return text;
        int cutAt = MAX_TEXT_CHARS;
        if (Character.isLowSurrogate(text.charAt(cutAt)) && cutAt > 0) cutAt--;
        return text.substring(0, cutAt) + TRUNCATION_SUFFIX;
    }
}
