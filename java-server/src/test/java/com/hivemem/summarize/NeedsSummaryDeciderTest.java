package com.hivemem.summarize;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NeedsSummaryDeciderTest {

    @Test
    void doesNotNeedSummary_whenSummaryProvided() {
        assertFalse(NeedsSummaryDecider.needsSummary("a".repeat(2000), "I am a summary", 500));
    }

    @Test
    void doesNotNeedSummary_whenContentShort() {
        assertFalse(NeedsSummaryDecider.needsSummary("short content", null, 500));
        assertFalse(NeedsSummaryDecider.needsSummary("a".repeat(500), null, 500));
    }

    @Test
    void needsSummary_whenLongContentAndNoSummary() {
        assertTrue(NeedsSummaryDecider.needsSummary("a".repeat(501), null, 500));
        assertTrue(NeedsSummaryDecider.needsSummary("a".repeat(2000), "", 500));
        assertTrue(NeedsSummaryDecider.needsSummary("a".repeat(2000), "   ", 500));
    }

    @Test
    void doesNotNeedSummary_whenContentNull() {
        assertFalse(NeedsSummaryDecider.needsSummary(null, null, 500));
    }

    @Test
    void defaultThresholdMatches500() {
        assertTrue(NeedsSummaryDecider.needsSummary("a".repeat(501), null));
        assertFalse(NeedsSummaryDecider.needsSummary("a".repeat(500), null));
    }

    /** Embeddability (max_chars, backend-dependent) and enrichment (500 chars) are
     *  different questions. An earlier design moved them together, which would have
     *  stopped tagging needs_summary for the 1101 cells between 500 and 8000 chars —
     *  and with it key_points, insight, tags and KG fact extraction, since those run
     *  inside the same summarizer call. This test exists to keep them apart. */
    @Test
    void enrichmentThresholdIsIndependentOfTheEmbedCap() {
        assertEquals(500, NeedsSummaryDecider.DEFAULT_THRESHOLD_CHARS);
        assertTrue(NeedsSummaryDecider.needsSummary("x".repeat(3000), null),
                "a 3000-char cell is embeddable on Ollama but still needs enrichment");
        assertFalse(NeedsSummaryDecider.needsSummary("x".repeat(3000), "a summary"));
    }
}
