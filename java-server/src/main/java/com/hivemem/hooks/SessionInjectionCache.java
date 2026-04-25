package com.hivemem.hooks;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Component
public class SessionInjectionCache {

    private record Key(String sessionId, UUID cellId) {}

    private final Cache<Key, Integer> cache;
    private final int dedupWindowTurns;

    public SessionInjectionCache(Duration ttl, int dedupWindowTurns) {
        this.dedupWindowTurns = dedupWindowTurns;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(50_000)
                .build();
    }

    public SessionInjectionCache() {
        this(Duration.ofHours(1), 5);
    }

    public void recordInjection(String sessionId, UUID cellId, int turn) {
        cache.put(new Key(sessionId, cellId), turn);
    }

    public boolean recentlyInjected(String sessionId, UUID cellId, int currentTurn) {
        Integer recordedTurn = cache.getIfPresent(new Key(sessionId, cellId));
        return recordedTurn != null && (currentTurn - recordedTurn) < dedupWindowTurns;
    }
}
