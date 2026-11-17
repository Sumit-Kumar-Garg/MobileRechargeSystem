package com.app.recharge.ratelimit;

import com.app.recharge.common.BusinessException;
import com.app.recharge.common.ErrorCode;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Blocks card-testing: after repeated failed payments a user is locked out for the rest of the window. */
@Component
public class AbuseGuard {

    private final RateLimiter limiter;
    private final RateLimitProperties.FailedPayments limits;

    /** Limits from app.ratelimit.failed-payments (default: 5 declined payments per hour). */
    public AbuseGuard(RateLimiter limiter, RateLimitProperties props) {
        this.limiter = limiter;
        this.limits = props.failedPayments();
    }

    /** Throws RATE_LIMITED if the user has reached the failed-payment limit in the current window. */
    public void assertPaymentAllowed(UUID userId) {
        if (limiter.current(key(userId)) >= limits.max()) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "Too many failed payments. Please try again later.");
        }
    }

    /** Counts one failed payment for the user in the current window. */
    public void recordPaymentFailure(UUID userId) {
        limiter.hit(key(userId), Integer.MAX_VALUE, limits.window());
    }

    private static String key(UUID userId) {
        return "payfail:" + userId;
    }
}
