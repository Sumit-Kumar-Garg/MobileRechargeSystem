package com.app.recharge.ratelimit;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Abuse-protection limits (app.ratelimit.*). Every value has a default equal to the original hard-coded limit and
 * can be overridden per environment; application.yml maps each to an environment variable.
 *
 * @param enabled         per-user / per-IP request limits on or off (the failed-payment lockout always applies)
 * @param store           memory (one instance) | redis (shared, required with several instances)
 * @param globalPerMinute ceiling for all requests of one user or IP per minute
 * @param policies        per-endpoint limits by policy name (see {@link RateLimitFilter#DEFAULT_POLICIES});
 *                        each entry may override the limit, the window, or both
 * @param failedPayments  card-testing lockout: after {@code max} declined payments in {@code window}
 * @param requestSize     largest accepted request body, in general and for telemetry batches
 */
@ConfigurationProperties("app.ratelimit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("memory") String store,
        @DefaultValue("120") int globalPerMinute,
        Map<String, PolicyLimit> policies,
        @DefaultValue FailedPayments failedPayments,
        @DefaultValue RequestSize requestSize) {

    /** Override of one endpoint policy; a null field keeps that policy's default. */
    public record PolicyLimit(Integer limit, Duration window) {
        public PolicyLimit {
            if (limit != null && limit < 1) {
                throw new IllegalArgumentException("rate limit policy limit must be at least 1");
            }
            requirePositive(window, "rate limit policy window");
        }
    }

    public record FailedPayments(@DefaultValue("5") int max, @DefaultValue("1h") Duration window) {
        public FailedPayments {
            if (max < 1) {
                throw new IllegalArgumentException("app.ratelimit.failed-payments.max must be at least 1");
            }
            requirePositive(window, "app.ratelimit.failed-payments.window");
        }
    }

    public record RequestSize(@DefaultValue("16KB") DataSize maxBody, @DefaultValue("256KB") DataSize maxTelemetryBody) {
        public RequestSize {
            if (maxBody.toBytes() < 1 || maxTelemetryBody.toBytes() < 1) {
                throw new IllegalArgumentException("app.ratelimit.request-size.* must be positive");
            }
        }
    }

    /** Checks the values and fills in nested defaults (useful when constructed directly, e.g. in tests). */
    @ConstructorBinding
    public RateLimitProperties {
        if (globalPerMinute < 1) {
            throw new IllegalArgumentException("app.ratelimit.global-per-minute must be at least 1");
        }
        policies = policies == null ? Map.of() : Map.copyOf(policies);
        failedPayments = failedPayments == null ? new FailedPayments(5, Duration.ofHours(1)) : failedPayments;
        requestSize = requestSize == null
                ? new RequestSize(DataSize.ofKilobytes(16), DataSize.ofKilobytes(256)) : requestSize;
    }

    /** All defaults except the global ceiling, for tests. */
    public RateLimitProperties(boolean enabled, String store, int globalPerMinute) {
        this(enabled, store, globalPerMinute, Map.of(), null, null);
    }

    /** The original defaults, for tests and components that run without Spring. */
    public static RateLimitProperties defaults() {
        return new RateLimitProperties(true, "memory", 120);
    }

    private static void requirePositive(Duration d, String name) {
        if (d != null && (d.isZero() || d.isNegative())) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
