package com.hivemem.attachment;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class KrokiAttachmentParserTest {

    @Test
    void supportsAllFourMimeTypes() {
        KrokiAttachmentParser p = new KrokiAttachmentParser();
        assertTrue(p.supports("text/x-mermaid"));
        assertTrue(p.supports("text/x-plantuml"));
        assertTrue(p.supports("text/vnd.graphviz"));
        assertTrue(p.supports("text/x-d2"));
        assertFalse(p.supports("text/plain"));
        assertFalse(p.supports(null));
    }

    @Test
    void parseExtractsTextWithoutThumbnail() throws Exception {
        KrokiAttachmentParser p = new KrokiAttachmentParser();
        byte[] body = "graph TD\n  A --> B\n".getBytes(StandardCharsets.UTF_8);
        ParseResult r = p.parse(new ByteArrayInputStream(body));
        assertEquals("graph TD\n  A --> B", r.extractedText());
        assertFalse(r.hasThumbnail());
        assertFalse(r.scanLikely());
    }

    @Test
    void parseBlankReturnsNullText() throws Exception {
        KrokiAttachmentParser p = new KrokiAttachmentParser();
        ParseResult r = p.parse(new ByteArrayInputStream("   \n  ".getBytes(StandardCharsets.UTF_8)));
        assertNull(r.extractedText());
    }
}
