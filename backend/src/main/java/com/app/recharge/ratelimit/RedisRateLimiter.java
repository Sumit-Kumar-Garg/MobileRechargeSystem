package com.app.recharge.ratelimit;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Shared counters so limits hold across all instances. INCR + PEXPIRE run atomically in one Lua script. */
@Component
@ConditionalOnProperty(name = "app.ratelimit.store", havingValue = "redis")
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    private static final DefaultRedisScript<Long> HIT = new DefaultRedisScript<>("""
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return c
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Fails open: if Redis is unreachable the request is allowed. Rate limiting protects the service, so it
     * must not become the reason the service (and every recharge) is down. Watch the warning log / Redis
     * health; the gateway keeps its own coarse limits meanwhile.
     */
    @Override
    public RateLimitResult hit(String key, int limit, Duration window) {
        try {
            String k = "rl:" + key;
            Long count = redis.execute(HIT, List.of(k), String.valueOf(window.toMillis()));
            long c = count == null ? 1 : count;
            Long ttl = redis.getExpire(k, TimeUnit.SECONDS);
            return new RateLimitResult(c <= limit, Math.max(0, limit - c), ttl == null || ttl < 1 ? 1 : ttl);
        } catch (RuntimeException e) {
            log.warn("Rate limiter store unavailable, allowing the request: {}", e.getMessage());
            return new RateLimitResult(true, limit, 1);
        }
    }

    /** Hits in the key's current window; 0 if none, or if Redis is unreachable. */
    @Override
    public long current(String key) {
        try {
            String v = redis.opsForValue().get("rl:" + key);
            return v == null ? 0 : Long.parseLong(v);
        } catch (RuntimeException e) {
            log.warn("Rate limiter store unavailable: {}", e.getMessage());
            return 0;
        }
    }
}
