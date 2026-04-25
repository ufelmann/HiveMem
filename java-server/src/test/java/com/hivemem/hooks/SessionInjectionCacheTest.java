package com.hivemem.hooks;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SessionInjectionCacheTest {

    @Test
    void recentlyInjectedTrueWithinDedupWindow() {
        var cache = new SessionInjectionCache(Duration.ofHours(1), 5);
        UUID cell = UUID.randomUUID();
        cache.recordInjection("session-1", cell, 10);
        assertThat(cache.recentlyInjected("session-1", cell, 12)).isTrue();
    }

    @Test
    void recentlyInjectedFalseOutsideDedupWindow() {
        var cache = new SessionInjectionCache(Duration.ofHours(1), 5);
        UUID cell = UUID.randomUUID();
        cache.recordInjection("session-1", cell, 10);
        assertThat(cache.recentlyInjected("session-1", cell, 17)).isFalse();
    }

    @Test
    void differentSessionsAreIsolated() {
        var cache = new SessionInjectionCache(Duration.ofHours(1), 5);
        UUID cell = UUID.randomUUID();
        cache.recordInjection("session-1", cell, 1);
        assertThat(cache.recentlyInjected("session-2", cell, 1)).isFalse();
    }

    @Test
    void unknownCellIsNotConsideredRecentlyInjected() {
        var cache = new SessionInjectionCache(Duration.ofHours(1), 5);
        assertThat(cache.recentlyInjected("session-1", UUID.randomUUID(), 1)).isFalse();
    }

    @Test
    void boundaryAtExactWindowEdgeIsExcluded() {
        // dedup window = 5 means turns t and t+1..t+4 are suppressed; turn t+5 is allowed
        var cache = new SessionInjectionCache(Duration.ofHours(1), 5);
        UUID cell = UUID.randomUUID();
        cache.recordInjection("s", cell, 0);
        assertThat(cache.recentlyInjected("s", cell, 4)).isTrue();
        assertThat(cache.recentlyInjected("s", cell, 5)).isFalse();
    }
}
