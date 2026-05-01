package com.hivemem.attachment;

public record ParseResult(String extractedText, byte[] thumbnail, String thumbnailMimeType) {

    public static ParseResult textOnly(String text) {
        return new ParseResult(text, null, null);
    }

    public static ParseResult withThumbnail(String text, byte[] thumbnail) {
        return new ParseResult(text, thumbnail, "image/jpeg");
    }

    public static ParseResult empty() {
        return new ParseResult(null, null, null);
    }

    public boolean hasThumbnail() {
        return thumbnail != null && thumbnail.length > 0;
    }
}
