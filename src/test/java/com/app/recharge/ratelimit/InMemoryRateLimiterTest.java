package com.app.recharge.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.recharge.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemoryRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-07T10:00:00Z"));
    private final InMemoryRateLimiter limiter = new InMemoryRateLimiter(clock);

    @Test
    void allowsUpToTheLimitThenBlocks() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.hit("k", 3, Duration.ofMinutes(1)).allowed()).isTrue();
        }
        RateLimitResult blocked = limiter.hit("k", 3, Duration.ofMinutes(1));
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.remaining()).isZero();
        assertThat(blocked.retryAfterSeconds()).isBetween(1L, 60L);
    }

    @Test
    void remainingCountsDown() {
        assertThat(limiter.hit("k", 3, Duration.ofMinutes(1)).remaining()).isEqualTo(2);
        assertThat(limiter.hit("k", 3, Duration.ofMinutes(1)).remaining()).isEqualTo(1);
    }

    @Test
    void windowResetsAfterItExpires() {
        for (int i = 0; i < 4; i++) {
            limiter.hit("k", 3, Duration.ofMinutes(1));
        }
        clock.advance(Duration.ofMinutes(1));

        assertThat(limiter.hit("k", 3, Duration.ofMinutes(1)).allowed()).isTrue();
        assertThat(limiter.current("k")).isEqualTo(1);
    }

    @Test
    void keysAreIndependent() {
        limiter.hit("a", 1, Duration.ofMinutes(1));
        assertThat(limiter.hit("a", 1, Duration.ofMinutes(1)).allowed()).isFalse();
        assertThat(limiter.hit("b", 1, Duration.ofMinutes(1)).allowed()).isTrue();
    }

    @Test
    void currentDoesNotCount() {
        assertThat(limiter.current("never")).isZero();
        limiter.hit("k", 5, Duration.ofMinutes(1));
        assertThat(limiter.current("k")).isEqualTo(1);
        assertThat(limiter.current("k")).isEqualTo(1);
    }

    @Test
    void currentIsZeroOnceTheWindowExpired() {
        limiter.hit("k", 5, Duration.ofMinutes(1));
        clock.advance(Duration.ofMinutes(2));
        assertThat(limiter.current("k")).isZero();
    }
}
