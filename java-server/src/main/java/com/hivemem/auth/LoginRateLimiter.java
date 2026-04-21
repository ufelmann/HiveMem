package com.hivemem.auth;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LoginRateLimiter {

    private static final int MAX_ATTEMPTS = 5;
    private static final long BLOCK_DURATION_SECONDS = 15 * 60;

    private record Failure(int count, Instant blockedUntil) {}

    private final Clock clock;
    private final ConcurrentHashMap<String, Failure> failures = new ConcurrentHashMap<>();

    public LoginRateLimiter() {
        this(Clock.systemUTC());
    }

    LoginRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public boolean isBlocked(String ip) {
        Failure f = failures.get(ip);
        if (f == null || f.blockedUntil() == null) return false;
        if (clock.instant().isAfter(f.blockedUntil())) {
            failures.remove(ip);
            return false;
        }
        return true;
    }

    public void recordFailure(String ip) {
        failures.merge(ip, new Failure(1, null), (existing, v) -> {
            int count = existing.count() + 1;
            Instant blocked = count >= MAX_ATTEMPTS
                    ? clock.instant().plusSeconds(BLOCK_DURATION_SECONDS)
                    : null;
            return new Failure(count, blocked);
        });
    }

    public void clearFailures(String ip) {
        failures.remove(ip);
    }

    /** Clears all tracked failures. Intended for test setup. */
    public void clearAll() {
        failures.clear();
    }
}
