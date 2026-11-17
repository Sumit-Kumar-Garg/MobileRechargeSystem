package com.app.recharge.telemetry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Internal event format on Kafka and in storage. Only a pseudonymous user id, never contact data. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TelemetryEnvelope(
        UUID id,
        String type,
        String name,
        Instant occurredAt,
        String userPseudoId,
        String sessionId,
        UUID journeyId,
        UUID orderId,
        String source,
        Map<String, Object> props,
        String appVersion) { }
