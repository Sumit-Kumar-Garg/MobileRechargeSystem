package com.app.recharge.notification;

import com.app.recharge.common.RechargeMetrics;
import com.app.recharge.common.Topics;
import com.app.recharge.order.OrderEventPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/**
 * Consumes order events. Non-blocking retries (5s, 30s, 3min) go through retry topics so one failing message
 * never blocks the partition; unparseable messages skip retries and land on the DLT, which alerts.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final NotificationService notifications;
    private final ObjectMapper mapper;
    private final RechargeMetrics metrics;

    public OrderEventConsumer(NotificationService notifications, ObjectMapper mapper, RechargeMetrics metrics) {
        this.notifications = notifications;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    /**
     * Kafka listener for order events, with non-blocking retries after 5 s, 30 s and 3 min before the dead-letter
     * topic.
     */
    @RetryableTopic(attempts = "4",
            backoff = @Backoff(delay = 5000, multiplier = 6.0, maxDelay = 300000),
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            exclude = PoisonMessageException.class,
            traversingCauses = "true",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE)
    @KafkaListener(topics = Topics.ORDER_EVENTS, groupId = "notification")
    public void onMessage(String payload) {
        process(payload);
    }

    /**
     * Parses the event and hands it to the notification service. Unparseable JSON throws PoisonMessageException, which
     * skips the retries.
     */
    void process(String payload) {
        OrderEventPayload event;
        try {
            event = mapper.readValue(payload, OrderEventPayload.class);
        } catch (JsonProcessingException e) {
            throw new PoisonMessageException("Unparseable order event", e);
        }
        notifications.handle(event);
    }

    /** Handles an event that ended on the dead-letter topic: counts it and logs its size only, never its content. */
    @DltHandler
    public void dlt(String payload) {
        metrics.dlt(Topics.ORDER_EVENTS);
        log.error("Order event moved to DLT ({} bytes). Investigate and replay.", payload == null ? 0 : payload.length());
    }
}
