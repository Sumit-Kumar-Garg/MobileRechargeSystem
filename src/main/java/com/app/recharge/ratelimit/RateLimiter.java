package com.app.recharge.ratelimit;

import java.time.Duration;

/** Fixed-window counter. In-memory for a single node, Redis for several nodes (select with app.ratelimit.store). */
public interface RateLimiter {

    /** Counts one hit and reports whether it is still within {@code limit} for the current window. */
    RateLimitResult hit(String key, int limit, Duration window);

    /** Hits counted in the current window, without counting a new one. */
    long current(String key);
}
