package com.hivemem.attachment;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MimeTypeResolverTest {

    @Test
    void mapsMermaidExtensions() {
        assertEquals("text/x-mermaid", MimeTypeResolver.resolve("text/plain", "diagram.mmd"));
        assertEquals("text/x-mermaid", MimeTypeResolver.resolve("text/plain", "diagram.mermaid"));
    }

    @Test
    void mapsPlantUmlExtensions() {
        assertEquals("text/x-plantuml", MimeTypeResolver.resolve("text/plain", "flow.puml"));
        assertEquals("text/x-plantuml", MimeTypeResolver.resolve("text/plain", "flow.plantuml"));
    }

    @Test
    void mapsGraphvizExtensions() {
        assertEquals("text/vnd.graphviz", MimeTypeResolver.resolve("text/plain", "g.dot"));
        assertEquals("text/vnd.graphviz", MimeTypeResolver.resolve("application/octet-stream", "g.gv"));
    }

    @Test
    void mapsD2Extension() {
        assertEquals("text/x-d2", MimeTypeResolver.resolve("text/plain", "arch.d2"));
    }

    @Test
    void specificMimeTypeIsPreserved() {
        assertEquals("application/pdf", MimeTypeResolver.resolve("application/pdf", "weird.dot"));
        assertEquals("image/png", MimeTypeResolver.resolve("image/png", "x.dot"));
    }

    @Test
    void unknownExtensionFallsThrough() {
        assertEquals("text/plain", MimeTypeResolver.resolve("text/plain", "readme.txt"));
        assertEquals("text/plain", MimeTypeResolver.resolve("text/plain", null));
    }

    @Test
    void caseInsensitiveExtensions() {
        assertEquals("text/x-mermaid", MimeTypeResolver.resolve("text/plain", "Diagram.MMD"));
    }
}
