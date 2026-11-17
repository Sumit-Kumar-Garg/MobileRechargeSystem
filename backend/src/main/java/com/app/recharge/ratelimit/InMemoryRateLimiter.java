package com.app.recharge.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.ratelimit.store", havingValue = "memory", matchIfMissing = true)
public class InMemoryRateLimiter implements RateLimiter {

    private static final int PURGE_THRESHOLD = 100_000;

    private record Window(long startMs, long lengthMs, long count) {
        boolean expired(long now) {
            return now - startMs >= lengthMs;
        }
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    /** Logs a warning at startup: these counters are per instance, so limits multiply with every extra instance. */
    public InMemoryRateLimiter(Clock clock) {
        this.clock = clock;
        LoggerFactory.getLogger(InMemoryRateLimiter.class).warn("Rate limits are counted in this instance's memory. "
                + "With more than one instance set RATE_LIMIT_STORE=redis, or every limit is multiplied by the "
                + "number of instances (including the failed-payment lockout).");
    }

    /**
     * Counts one hit in the key's fixed window, starting a new window if the old one ended. Occasionally purges expired
     * windows to bound memory.
     */
    @Override
    public RateLimitResult hit(String key, int limit, Duration window) {
        long now = clock.millis();
        if (windows.size() > PURGE_THRESHOLD) {
            windows.entrySet().removeIf(e -> e.getValue().expired(now));
        }
        Window updated = windows.compute(key, (k, w) ->
                (w == null || w.expired(now)) ? new Window(now, window.toMillis(), 1) : new Window(w.startMs(), w.lengthMs(), w.count() + 1));
        long remaining = Math.max(0, limit - updated.count());
        long retryAfter = Math.max(1, (updated.startMs() + updated.lengthMs() - now + 999) / 1000);
        return new RateLimitResult(updated.count() <= limit, remaining, retryAfter);
    }

    /** Hits in the key's current window, 0 if none or expired. */
    @Override
    public long current(String key) {
        Window w = windows.get(key);
        return (w == null || w.expired(clock.millis())) ? 0 : w.count();
    }
}
