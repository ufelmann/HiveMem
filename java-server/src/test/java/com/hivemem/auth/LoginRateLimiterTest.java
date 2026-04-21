package com.hivemem.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRateLimiterTest {

    @Test
    void notBlockedInitially() {
        assertThat(new LoginRateLimiter().isBlocked("1.2.3.4")).isFalse();
    }

    @Test
    void notBlockedAfterFourFailures() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        for (int i = 0; i < 4; i++) limiter.recordFailure("1.2.3.4");
        assertThat(limiter.isBlocked("1.2.3.4")).isFalse();
    }

    @Test
    void blockedAfterFiveFailures() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        for (int i = 0; i < 5; i++) limiter.recordFailure("1.2.3.4");
        assertThat(limiter.isBlocked("1.2.3.4")).isTrue();
    }

    @Test
    void clearResetsBlock() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        for (int i = 0; i < 5; i++) limiter.recordFailure("1.2.3.4");
        limiter.clearFailures("1.2.3.4");
        assertThat(limiter.isBlocked("1.2.3.4")).isFalse();
    }

    @Test
    void differentIpsAreIndependent() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        for (int i = 0; i < 5; i++) limiter.recordFailure("1.2.3.4");
        assertThat(limiter.isBlocked("5.6.7.8")).isFalse();
    }

    @Test
    void blockExpiresAfterBlockDuration() {
        Instant[] now = {Instant.parse("2026-01-01T00:00:00Z")};
        Clock mutableClock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now[0]; }
        };
        LoginRateLimiter limiter = new LoginRateLimiter(mutableClock);
        for (int i = 0; i < 5; i++) limiter.recordFailure("ip");
        assertThat(limiter.isBlocked("ip")).isTrue();

        now[0] = now[0].plusSeconds(16 * 60); // 16 minutes later
        assertThat(limiter.isBlocked("ip")).isFalse();
    }
}
