package com.app.recharge.telemetry;

import com.app.recharge.common.RechargeMetrics;
import com.app.recharge.consent.ConsentPurpose;
import com.app.recharge.consent.ConsentService;
import com.app.recharge.crypto.PiiCrypto;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Validates and filters a client batch. Operational telemetry (network_call) needs no consent and carries no
 * PII; behavioral events (journey, lifecycle, config_change) are accepted only after the user opted in.
 */
@Service
public class TelemetryService {

    public record IngestResult(int accepted, int dropped) { }

    private static final Set<String> BEHAVIORAL = Set.of("journey", "lifecycle", "config_change");
    private static final Duration MAX_FUTURE = Duration.ofMinutes(5);
    private static final Duration MAX_AGE = Duration.ofDays(7);

    private final ConsentService consent;
    private final PiiGuard guard;
    private final PiiCrypto crypto;
    private final TelemetrySink sink;
    private final RechargeMetrics metrics;
    private final Clock clock;

    public TelemetryService(ConsentService consent, PiiGuard guard, PiiCrypto crypto, TelemetrySink sink,
                            RechargeMetrics metrics, Clock clock) {
        this.consent = consent;
        this.guard = guard;
        this.crypto = crypto;
        this.sink = sink;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Filters a client batch and publishes what is left. Drops behavioural events without analytics consent, events
     * with an invalid id, and events timed more than 5 minutes ahead or 7 days back. Cleans properties of PII and
     * replaces the user id with a pseudonymous id. Returns accepted and dropped counts.
     */
    public IngestResult ingest(UUID userId, TelemetryBatchRequest request) {
        boolean analytics = consent.isGranted(userId, ConsentPurpose.ANALYTICS);
        String pseudoId = crypto.hash("user:" + userId);
        Instant now = clock.instant();
        List<TelemetryEnvelope> accepted = new ArrayList<>();
        int dropped = 0;

        for (TelemetryBatchRequest.EventDto event : request.events()) {
            if (BEHAVIORAL.contains(event.type()) && !analytics) {
                dropped++;
                metrics.telemetryDropped("no_consent");
                continue;
            }
            UUID id = parse(event.id());
            if (id == null) {
                dropped++;
                metrics.telemetryDropped("bad_id");
                continue;
            }
            Instant at = event.occurredAt();
            if (at == null || at.isAfter(now.plus(MAX_FUTURE)) || at.isBefore(now.minus(MAX_AGE))) {
                dropped++;
                metrics.telemetryDropped("bad_time");
                continue;
            }
            PiiGuard.Result clean = guard.sanitize(event.props());
            for (int i = 0; i < clean.redactions(); i++) {
                metrics.piiRedaction();
            }
            accepted.add(new TelemetryEnvelope(id, event.type(), event.name(), at, pseudoId, request.sessionId(),
                    event.journeyId(), event.orderId(), "android", clean.props(), request.appVersion()));
        }
        if (!accepted.isEmpty()) {
            sink.publish(accepted);
        }
        return new IngestResult(accepted.size(), dropped);
    }

    /** Parses a UUID, or returns null if it is not one. */
    private static UUID parse(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
