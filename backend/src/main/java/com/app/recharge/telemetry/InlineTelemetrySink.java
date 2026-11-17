package com.app.recharge.telemetry;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "false")
public class InlineTelemetrySink implements TelemetrySink {

    private final TelemetryProcessor processor;

    public InlineTelemetrySink(TelemetryProcessor processor) {
        this.processor = processor;
    }

    /** Stores the events directly, one by one, in the caller's thread (used when Kafka is disabled). */
    @Override
    public void publish(List<TelemetryEnvelope> events) {
        events.forEach(processor::process);
    }
}
