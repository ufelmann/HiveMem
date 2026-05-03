package com.hivemem.attachment;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextAttachmentParserTest {

    private final TextAttachmentParser parser = new TextAttachmentParser();

    @Test
    void supports_textMimeTypes() {
        assertTrue(parser.supports("text/plain"));
        assertTrue(parser.supports("text/markdown"));
        assertTrue(parser.supports("text/x-mermaid"));
    }

    @Test
    void supports_rejectsNonText() {
        assertFalse(parser.supports("image/png"));
        assertFalse(parser.supports("application/pdf"));
        assertFalse(parser.supports(null));
    }

    @Test
    void parse_returnsStrippedText() throws Exception {
        var result = parser.parse(new ByteArrayInputStream("  hello world  \n".getBytes(StandardCharsets.UTF_8)));
        assertEquals("hello world", result.extractedText());
    }

    @Test
    void parse_blankInputReturnsNullText() throws Exception {
        var result = parser.parse(new ByteArrayInputStream("   \n\t".getBytes(StandardCharsets.UTF_8)));
        assertNull(result.extractedText());
    }

    @Test
    void parse_handlesUtf8() throws Exception {
        var result = parser.parse(new ByteArrayInputStream("Grüße — naïve".getBytes(StandardCharsets.UTF_8)));
        assertEquals("Grüße — naïve", result.extractedText());
    }
}
