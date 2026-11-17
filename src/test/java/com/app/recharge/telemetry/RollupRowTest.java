package com.app.recharge.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RollupRowTest {

    private static TelemetryEnvelope envelope(Map<String, Object> props, String appVersion) {
        return new TelemetryEnvelope(UUID.randomUUID(), "network_call", "api_call",
                Instant.parse("2026-11-07T10:42:17Z"), "pseudo", "session-1234", null, null, "android", props, appVersion);
    }

    private static Map<String, Object> props(Object endpoint, Object network, Object outcome, Object duration) {
        Map<String, Object> p = new HashMap<>();
        p.put("endpoint", endpoint);
        p.put("networkType", network);
        p.put("outcome", outcome);
        p.put("durationMs", duration);
        return p;
    }

    @Test
    void buildsABoundedRowFromAWellFormedEvent() {
        Map<String, Object> p = props("/v1/orders/{id}/pay", "cellular", "success", 480);
        p.put("serverDurationMs", 120);

        RollupRow row = RollupRow.from(envelope(p, "1.0.0"));

        assertThat(row.endpoint()).isEqualTo("/v1/orders/{id}/pay");
        assertThat(row.networkType()).isEqualTo("cellular");
        assertThat(row.error()).isFalse();
        assertThat(row.durationMs()).isEqualTo(480);
        assertThat(row.serverMs()).isEqualTo(120);
        assertThat(row.bucketHour()).isEqualTo(Instant.parse("2026-11-07T10:00:00Z"));
        assertThat(row.appVersion()).isEqualTo("1.0.0");
    }

    @Test
    void unknownEndpointsCollapseIntoOtherSoCardinalityStaysBounded() {
        RollupRow row = RollupRow.from(envelope(props("/v1/anything/" + UUID.randomUUID(), "wifi", "success", 50), "1.0"));
        assertThat(row.endpoint()).isEqualTo("other");
    }

    @Test
    void unknownNetworkTypesAndVersionsAreNormalized() {
        RollupRow row = RollupRow.from(envelope(props("/v1/plans", "5g-sa-beta", "success", 50), "bad version!"));
        assertThat(row.networkType()).isEqualTo("unknown");
        assertThat(row.appVersion()).isEqualTo("unknown");
    }

    @Test
    void anythingButSuccessCountsAsAnError() {
        assertThat(RollupRow.from(envelope(props("/v1/plans", "wifi", "timeout", 5000), "1.0")).error()).isTrue();
        assertThat(RollupRow.from(envelope(props("/v1/plans", "wifi", "http_error", 80), "1.0")).error()).isTrue();
        assertThat(RollupRow.from(envelope(props("/v1/plans", "wifi", null, 80), "1.0")).error()).isTrue();
    }

    @Test
    void eventsWithoutADurationAreIgnored() {
        Map<String, Object> p = props("/v1/plans", "wifi", "success", null);
        assertThat(RollupRow.from(envelope(p, "1.0"))).isNull();
        assertThat(RollupRow.from(envelope(null, "1.0"))).isNull();
    }

    @Test
    void absurdDurationsAreClamped() {
        assertThat(RollupRow.from(envelope(props("/v1/plans", "wifi", "success", 99_999_999), "1.0")).durationMs())
                .isEqualTo(600_000);
        assertThat(RollupRow.from(envelope(props("/v1/plans", "wifi", "success", -5), "1.0")).durationMs()).isZero();
    }
}
