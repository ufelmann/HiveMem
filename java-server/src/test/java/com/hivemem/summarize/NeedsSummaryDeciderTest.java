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
}
