package com.app.recharge.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.recharge.common.BusinessException;
import com.app.recharge.common.ErrorCode;
import com.app.recharge.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AbuseGuardTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-07T10:00:00Z"));
    private final AbuseGuard guard = new AbuseGuard(new InMemoryRateLimiter(clock), RateLimitProperties.defaults());
    private final RateLimitProperties.FailedPayments limits = RateLimitProperties.defaults().failedPayments();
    private final UUID user = UUID.randomUUID();

    @Test
    void allowsPaymentsBelowTheFailureThreshold() {
        for (int i = 0; i < limits.max() - 1; i++) {
            guard.recordPaymentFailure(user);
        }
        assertThatCode(() -> guard.assertPaymentAllowed(user)).doesNotThrowAnyException();
    }

    @Test
    void locksOutAfterTooManyFailures() {
        for (int i = 0; i < limits.max(); i++) {
            guard.recordPaymentFailure(user);
        }
        assertThatThrownBy(() -> guard.assertPaymentAllowed(user))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.RATE_LIMITED));
    }

    @Test
    void lockoutIsPerUserAndExpires() {
        for (int i = 0; i < limits.max(); i++) {
            guard.recordPaymentFailure(user);
        }
        assertThatCode(() -> guard.assertPaymentAllowed(UUID.randomUUID())).doesNotThrowAnyException();

        clock.advance(limits.window().plus(Duration.ofSeconds(1)));
        assertThatCode(() -> guard.assertPaymentAllowed(user)).doesNotThrowAnyException();
    }
}
