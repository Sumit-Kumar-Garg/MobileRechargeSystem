package com.app.recharge.telemetry;

import com.app.recharge.common.RechargeMetrics;
import com.app.recharge.common.Topics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class TelemetryConsumer {

    private static final Logger log = LoggerFactory.getLogger(TelemetryConsumer.class);

    private final TelemetryProcessor processor;
    private final ObjectMapper mapper;
    private final RechargeMetrics metrics;

    public TelemetryConsumer(TelemetryProcessor processor, ObjectMapper mapper, RechargeMetrics metrics) {
        this.processor = processor;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    /**
     * Kafka listener for telemetry events, retried twice (after 2 s and 6 s) before the dead-letter topic. Unparseable
     * JSON goes straight to the DLT.
     */
    @RetryableTopic(attempts = "3",
            backoff = @Backoff(delay = 2000, multiplier = 3.0),
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            exclude = JsonProcessingException.class,
            traversingCauses = "true")
    @KafkaListener(topics = Topics.CLIENT_TELEMETRY, groupId = "telemetry")
    public void onMessage(String payload) throws JsonProcessingException {
        handle(payload);
    }

    /** Parses the event and stores it. */
    void handle(String payload) throws JsonProcessingException {
        processor.process(mapper.readValue(payload, TelemetryEnvelope.class));
    }

    /** Handles an event that ended on the dead-letter topic: counts it and logs its size only. */
    @DltHandler
    public void dlt(String payload) {
        metrics.dlt(Topics.CLIENT_TELEMETRY);
        log.error("Telemetry event moved to DLT ({} bytes)", payload == null ? 0 : payload.length());
    }
}
