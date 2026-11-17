package com.app.recharge.telemetry;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One client network call reduced to its aggregate contribution. Dimensions are bounded on purpose
 * (allowlisted endpoint templates, fixed network types) so a client can never blow up cardinality.
 */
record RollupRow(Instant bucketHour, String endpoint, String networkType, String appVersion,
                 boolean error, long durationMs, long serverMs) {

    static final Set<String> ENDPOINTS = Set.of(
            "/v1/plans", "/v1/numbers", "/v1/orders", "/v1/orders/{id}", "/v1/orders/{id}/pay",
            "/v1/recommendations", "/v1/devices", "/v1/notification-preferences",
            "/v1/consents/personalization", "/v1/consents/analytics", "/v1/telemetry/batch");
    static final Set<String> NETWORK_TYPES = Set.of("wifi", "cellular", "ethernet", "vpn", "unknown");
    private static final Pattern VERSION = Pattern.compile("^[0-9A-Za-z.\\-]{1,20}$");
    private static final long MAX_MS = 600_000;

    /** @return null when the event has no usable duration */
    static RollupRow from(TelemetryEnvelope e) {
        Map<String, Object> p = e.props();
        if (p == null || !(p.get("durationMs") instanceof Number duration)) {
            return null;
        }
        String endpoint = p.get("endpoint") instanceof String s && ENDPOINTS.contains(s) ? s : "other";
        String network = p.get("networkType") instanceof String s && NETWORK_TYPES.contains(s) ? s : "unknown";
        String version = e.appVersion() != null && VERSION.matcher(e.appVersion()).matches() ? e.appVersion() : "unknown";
        boolean error = !(p.get("outcome") instanceof String o) || !"success".equals(o);
        long server = p.get("serverDurationMs") instanceof Number n ? clamp(n.longValue()) : 0;
        return new RollupRow(e.occurredAt().truncatedTo(ChronoUnit.HOURS), endpoint, network, version,
                error, clamp(duration.longValue()), server);
    }

    /** Limits a duration to 0 .. 10 minutes. */
    private static long clamp(long v) {
        return Math.max(0, Math.min(v, MAX_MS));
    }
}
