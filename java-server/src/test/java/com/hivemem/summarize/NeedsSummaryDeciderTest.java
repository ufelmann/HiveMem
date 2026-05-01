package com.hivemem.summarize;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NeedsSummaryDeciderTest {

    private final NeedsSummaryDecider decider = new NeedsSummaryDecider(500);

    @Test
    void doesNotNeedSummary_whenSummaryProvided() {
        assertFalse(decider.needsSummary("a".repeat(2000), "I am a summary"));
    }

    @Test
    void doesNotNeedSummary_whenContentShort() {
        assertFalse(decider.needsSummary("short content", null));
        assertFalse(decider.needsSummary("a".repeat(500), null));
    }

    @Test
    void needsSummary_whenLongContentAndNoSummary() {
        assertTrue(decider.needsSummary("a".repeat(501), null));
        assertTrue(decider.needsSummary("a".repeat(2000), ""));
        assertTrue(decider.needsSummary("a".repeat(2000), "   "));
    }

    @Test
    void doesNotNeedSummary_whenContentNull() {
        assertFalse(decider.needsSummary(null, null));
    }
}
