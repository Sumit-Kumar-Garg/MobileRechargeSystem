package com.app.recharge.telemetry;

import java.util.List;

/** Where accepted events go. Kafka in production; processed inline when Kafka is disabled (tests, local). */
public interface TelemetrySink {
    /** Hands accepted events on for storage. */
    void publish(List<TelemetryEnvelope> events);
}
