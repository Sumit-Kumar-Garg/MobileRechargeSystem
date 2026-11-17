package com.app.recharge.telemetry;

import com.app.recharge.common.BusinessException;
import com.app.recharge.common.ErrorCode;
import com.app.recharge.common.Topics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Key = pseudonymous user id, so one user's events stay ordered within a partition. */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaTelemetrySink implements TelemetrySink {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;

    public KafkaTelemetrySink(KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
        this.kafka = kafka;
        this.mapper = mapper;
    }

    /**
     * Sends the events to the telemetry topic and waits up to 3 seconds for all acknowledgements. Throws
     * SERVICE_UNAVAILABLE if Kafka does not confirm in time.
     */
    @Override
    public void publish(List<TelemetryEnvelope> events) {
        try {
            List<CompletableFuture<?>> sends = new ArrayList<>();
            for (TelemetryEnvelope e : events) {
                sends.add(kafka.send(Topics.CLIENT_TELEMETRY, e.userPseudoId(), mapper.writeValueAsString(e)));
            }
            CompletableFuture.allOf(sends.toArray(new CompletableFuture[0])).get(3, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize telemetry", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Telemetry is temporarily unavailable");
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Telemetry is temporarily unavailable");
        }
    }
}
