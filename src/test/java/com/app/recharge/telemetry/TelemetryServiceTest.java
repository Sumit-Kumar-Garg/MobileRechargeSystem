package com.app.recharge.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.recharge.common.RechargeMetrics;
import com.app.recharge.consent.ConsentPurpose;
import com.app.recharge.consent.ConsentService;
import com.app.recharge.crypto.CryptoProperties;
import com.app.recharge.crypto.PiiCrypto;
import com.app.recharge.support.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TelemetryServiceTest {

    private final ConsentService consent = mock(ConsentService.class);
    private final TelemetrySink sink = mock(TelemetrySink.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-07T10:00:00Z"));
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final UUID user = UUID.randomUUID();
    private PiiCrypto crypto;
    private TelemetryService service;

    private static String key(char c) {
        byte[] b = new byte[32];
        Arrays.fill(b, (byte) c);
        return Base64.getEncoder().encodeToString(b);
    }

    @BeforeEach
    void setUp() {
        crypto = new PiiCrypto(new CryptoProperties(key('a'), key('b')));
        service = new TelemetryService(consent, new PiiGuard(), crypto, sink, new RechargeMetrics(registry), clock);
        when(consent.isGranted(user, ConsentPurpose.ANALYTICS)).thenReturn(false);
    }

    private TelemetryBatchRequest.EventDto event(String type, String name, Instant at, Map<String, Object> props) {
        return new TelemetryBatchRequest.EventDto(UUID.randomUUID().toString(), type, name, at, null, null, props);
    }

    private TelemetryBatchRequest batch(TelemetryBatchRequest.EventDto... events) {
        return new TelemetryBatchRequest("session-12345678", "1.0.0", List.of(events));
    }

    @SuppressWarnings("unchecked")
    private List<TelemetryEnvelope> published() {
        ArgumentCaptor<List<TelemetryEnvelope>> captor = ArgumentCaptor.forClass(List.class);
        verify(sink).publish(captor.capture());
        return captor.getValue();
    }

    @Test
    void operationalEventsNeedNoConsent() {
        var result = service.ingest(user, batch(event("network_call", "api_call", clock.instant(), Map.of("durationMs", 100))));

        assertThat(result.accepted()).isEqualTo(1);
        assertThat(result.dropped()).isZero();
    }

    @Test
    void behavioralEventsAreDroppedWithoutConsent() {
        var result = service.ingest(user, batch(
                event("journey", "plans_viewed", clock.instant(), Map.of()),
                event("lifecycle", "app_foreground", clock.instant(), Map.of()),
                event("config_change", "orientation_changed", clock.instant(), Map.of()),
                event("network_call", "api_call", clock.instant(), Map.of("durationMs", 10))));

        assertThat(result.accepted()).isEqualTo(1);
        assertThat(result.dropped()).isEqualTo(3);
        assertThat(registry.counter("recharge.telemetry.dropped", "reason", "no_consent").count()).isEqualTo(3.0);
    }

    @Test
    void behavioralEventsAreAcceptedAfterOptIn() {
        when(consent.isGranted(user, ConsentPurpose.ANALYTICS)).thenReturn(true);

        var result = service.ingest(user, batch(event("journey", "plans_viewed", clock.instant(), Map.of("plan", "M-28"))));

        assertThat(result.accepted()).isEqualTo(1);
        TelemetryEnvelope e = published().get(0);
        assertThat(e.source()).isEqualTo("android");
        assertThat(e.userPseudoId()).isEqualTo(crypto.hash("user:" + user)).doesNotContain(user.toString());
        assertThat(e.sessionId()).isEqualTo("session-12345678");
        assertThat(e.appVersion()).isEqualTo("1.0.0");
    }

    @Test
    void malformedIdsAndTimestampsAreDropped() {
        when(consent.isGranted(user, ConsentPurpose.ANALYTICS)).thenReturn(true);
        var badId = new TelemetryBatchRequest.EventDto("not-a-uuid", "journey", "plans_viewed", clock.instant(), null, null, Map.of());

        var result = service.ingest(user, batch(
                badId,
                event("journey", "a", clock.instant().plus(Duration.ofHours(1)), Map.of()),
                event("journey", "b", clock.instant().minus(Duration.ofDays(8)), Map.of()),
                event("journey", "c", null, Map.of())));

        assertThat(result.accepted()).isZero();
        assertThat(result.dropped()).isEqualTo(4);
        verify(sink, never()).publish(any());
    }

    @Test
    void piiInPropsIsRedactedAndCounted() {
        when(consent.isGranted(user, ConsentPurpose.ANALYTICS)).thenReturn(true);

        service.ingest(user, batch(event("journey", "number_entered", clock.instant(),
                Map.of("number", "+491701234567", "ok", 1))));

        assertThat(published().get(0).props()).containsEntry("number", "[redacted]").containsEntry("ok", 1);
        assertThat(registry.counter("recharge.telemetry.pii_redactions").count()).isEqualTo(1.0);
    }

    @Test
    void nothingIsPublishedWhenEverythingWasDropped() {
        service.ingest(user, batch(event("journey", "plans_viewed", clock.instant(), Map.of())));
        verify(sink, never()).publish(any());
    }
}
