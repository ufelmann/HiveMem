package com.hivemem.auth;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimiter {

    static final int MAX_FAILED_ATTEMPTS = 5;
    static final long BAN_SECONDS = 900;

    private final Map<String, FailedAttempts> tracker = new ConcurrentHashMap<>();

    record FailedAttempts(int count, Instant lastAttempt) {}

    /** Returns remaining ban seconds, or 0 if not banned. */
    public long checkRateLimit(String ip) {
        FailedAttempts attempts = tracker.get(ip);
        if (attempts == null || attempts.count() < MAX_FAILED_ATTEMPTS) {
            return 0L;
        }
        long elapsed = Instant.now().getEpochSecond() - attempts.lastAttempt().getEpochSecond();
        if (elapsed >= BAN_SECONDS) {
            tracker.remove(ip);
            return 0L;
        }
        return BAN_SECONDS - elapsed;
    }

    public void recordFailure(String ip) {
        tracker.compute(ip, (key, existing) -> {
            int count = existing == null ? 1 : existing.count() + 1;
            return new FailedAttempts(count, Instant.now());
        });
    }

    public void clearFailures(String ip) {
        tracker.remove(ip);
    }

    /** Clears all tracked failures. Intended for test setup. */
    public void clearAll() {
        tracker.clear();
    }
}
