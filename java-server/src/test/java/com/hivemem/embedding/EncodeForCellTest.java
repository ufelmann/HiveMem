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

    /** Overrides {@link EmbeddingClient#maxChars()} to a non-default value so tests can prove
     *  {@code encodeForCell} consults {@code maxChars()} itself, not the {@code
     *  CONTENT_EMBED_MAX_CHARS} constant (which {@link RecordingClient} would otherwise mask,
     *  since its default happens to equal the constant). */
    private static class CustomCapClient extends RecordingClient {
        private final int cap;
        CustomCapClient(int cap) { this.cap = cap; }
        @Override public int maxChars() { return cap; }
    }

    @Test
    void usesContent_whenWithinCap_evenIfSummaryPresent() {
        RecordingClient c = new RecordingClient();
        List<Float> v = c.encodeForCell("short content", "the summary");
        assertEquals("short content", c.lastInput);
        assertEquals(List.of(0.1f, 0.2f), v);
    }

    @Test
    void fallsBackToSummary_whenContentExceedsCap() {
        RecordingClient c = new RecordingClient();
        String tooLong = "x".repeat(501);
        List<Float> v = c.encodeForCell(tooLong, "the summary");
        assertEquals("the summary", c.lastInput);
        assertEquals(List.of(0.1f, 0.2f), v);
    }

    @Test
    void returnsNull_whenContentTooLongAndNoSummary() {
        RecordingClient c = new RecordingClient();
        c.lastInput = "untouched";
        assertNull(c.encodeForCell("x".repeat(501), null));
        assertEquals("untouched", c.lastInput);
    }

    @Test
    void boundary_contentExactlyAtCapUsesContent() {
        RecordingClient c = new RecordingClient();
        String atCap = "x".repeat(500);
        List<Float> v = c.encodeForCell(atCap, "the summary");
        assertEquals(atCap, c.lastInput);
        assertEquals(List.of(0.1f, 0.2f), v);
    }

    @Test
    void treatsBlankSummaryAsAbsent() {
        RecordingClient c = new RecordingClient();
        List<Float> v = c.encodeForCell("short content", "   ");
        assertEquals("short content", c.lastInput);
        assertNotNull(v);
    }

    @Test
    void consultsMaxChars_notTheHistoricalConstant_forALowerCap() {
        // 8000-char content is far above CONTENT_EMBED_MAX_CHARS (500), but within a
        // custom, larger maxChars() — content must still win over the summary.
        CustomCapClient c = new CustomCapClient(8000);
        String content = "x".repeat(8000);
        c.encodeForCell(content, "the summary");
        assertEquals(content, c.lastInput);
    }

    @Test
    void consultsMaxChars_notTheHistoricalConstant_forAHigherCap() {
        // 50-char content is well within CONTENT_EMBED_MAX_CHARS (500), but a custom,
        // smaller maxChars() must still push it into the summary fallback.
        CustomCapClient c = new CustomCapClient(10);
        c.encodeForCell("this is 50 characters of content, well under 500!", "the summary");
        assertEquals("the summary", c.lastInput);
    }
}
