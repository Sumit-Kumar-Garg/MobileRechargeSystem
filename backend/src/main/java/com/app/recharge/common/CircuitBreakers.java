package com.app.recharge.common;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;

public final class CircuitBreakers {

    private CircuitBreakers() { }

    /** Creates a circuit breaker with the default window: last 20 calls, at least 10 calls before it can open. */
    public static CircuitBreaker create(String name, Class<? extends Throwable> recorded) {
        return create(name, recorded, 20, 10);
    }

    /**
     * Creates a count-based circuit breaker that opens at a 50% failure rate over the last {@code window} calls (after
     * at least {@code minCalls}), counts only {@code recorded} exceptions as failures, stays open 20 seconds, then lets
     * 3 trial calls through.
     */
    public static CircuitBreaker create(String name, Class<? extends Throwable> recorded, int window, int minCalls) {
        CircuitBreakerConfig cfg = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(window)
                .minimumNumberOfCalls(minCalls)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofSeconds(20))
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordExceptions(recorded)
                .build();
        return CircuitBreaker.of(name, cfg);
    }
}
