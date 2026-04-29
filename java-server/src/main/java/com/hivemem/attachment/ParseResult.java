package com.hivemem.attachment;

public record ParseResult(String extractedText, byte[] thumbnail, String thumbnailMimeType) {

    private static final int MAX_TEXT_CHARS = 10_000;

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
        return extractedText != null && extractedText.endsWith("… [truncated]");
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= MAX_TEXT_CHARS) return text;
        return text.substring(0, MAX_TEXT_CHARS) + "… [truncated]";
    }
}
