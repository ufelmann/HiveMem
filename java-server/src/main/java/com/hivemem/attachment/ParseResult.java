package com.hivemem.attachment;

public record ParseResult(String extractedText, byte[] thumbnail, String thumbnailMimeType, boolean scanLikely) {

    public static ParseResult textOnly(String text) {
        return new ParseResult(text, null, null, false);
    }

    public static ParseResult withThumbnail(String text, byte[] thumbnail) {
        return new ParseResult(text, thumbnail, "image/jpeg", false);
    }

    public static ParseResult withThumbnailAndScan(String text, byte[] thumbnail, boolean scanLikely) {
        return new ParseResult(text, thumbnail, "image/jpeg", scanLikely);
    }

    public static ParseResult empty() {
        return new ParseResult(null, null, null, false);
    }

    public boolean hasThumbnail() {
        return thumbnail != null && thumbnail.length > 0;
    }
}
