package com.hivemem.embedding;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EncodeForCellTest {

    /** Test client that records what it was asked to encode. */
    private static class RecordingClient implements EmbeddingClient {
        String lastInput;
        @Override public List<Float> encodeDocument(String text) {
            lastInput = text;
            return List.of(0.1f, 0.2f);
        }
        @Override public EmbeddingInfo getInfo() { return new EmbeddingInfo("test", 2); }
    }

    @Test
    void usesSummary_whenProvided() {
        RecordingClient c = new RecordingClient();
        List<Float> v = c.encodeForCell("very long content...", "the summary");
        assertEquals("the summary", c.lastInput);
        assertEquals(List.of(0.1f, 0.2f), v);
    }

    @Test
    void usesContent_whenSummaryNullAndContentShort() {
        RecordingClient c = new RecordingClient();
        List<Float> v = c.encodeForCell("short", null);
        assertEquals("short", c.lastInput);
        assertEquals(List.of(0.1f, 0.2f), v);
    }

    @Test
    void returnsNull_whenLongContentAndNoSummary() {
        RecordingClient c = new RecordingClient();
        c.lastInput = "untouched";
        List<Float> v = c.encodeForCell("a".repeat(600), null);
        assertNull(v);
        assertEquals("untouched", c.lastInput);
    }

    @Test
    void treatsBlankSummaryAsAbsent() {
        RecordingClient c = new RecordingClient();
        List<Float> v = c.encodeForCell("short content", "   ");
        assertEquals("short content", c.lastInput);
        assertNotNull(v);
    }
}
