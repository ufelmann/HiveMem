package com.hivemem.attachment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicVisionResponseParserTest {

    @Test
    void parsesCleanJson() {
        var r = AnthropicVisionResponseParser.parse(
                "{\"sub_type\":\"whiteboard_photo\",\"content\":\"text on whiteboard\"}");
        assertEquals("whiteboard_photo", r.subType());
        assertEquals("text on whiteboard", r.content());
    }

    @Test
    void parsesJsonInsideCodeFences() {
        var r = AnthropicVisionResponseParser.parse(
                "```json\n{\"sub_type\":\"document_scan\",\"content\":\"verbatim text\"}\n```");
        assertEquals("document_scan", r.subType());
        assertEquals("verbatim text", r.content());
    }

    @Test
    void parsesJsonWithProsePreamble() {
        var r = AnthropicVisionResponseParser.parse(
                "Here is the result: {\"sub_type\":\"photo_general\",\"content\":\"a dog\"}");
        assertEquals("photo_general", r.subType());
        assertEquals("a dog", r.content());
    }

    @Test
    void unknownSubTypeFallsBackToPhotoGeneral() {
        var r = AnthropicVisionResponseParser.parse(
                "{\"sub_type\":\"chart_screenshot\",\"content\":\"a chart\"}");
        assertEquals("photo_general", r.subType());
        assertEquals("a chart", r.content());
    }

    @Test
    void malformedJsonReturnsRawAsContentAndPhotoGeneral() {
        var r = AnthropicVisionResponseParser.parse("not json at all");
        assertEquals("photo_general", r.subType());
        assertEquals("not json at all", r.content());
    }

    @Test
    void missingContentReturnsEmptyString() {
        var r = AnthropicVisionResponseParser.parse(
                "{\"sub_type\":\"whiteboard_photo\"}");
        assertEquals("whiteboard_photo", r.subType());
        assertEquals("", r.content());
    }
}
