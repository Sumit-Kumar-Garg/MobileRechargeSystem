package com.app.recharge.outbox;

import com.app.recharge.common.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Retry and dead-letter topics are created by @RetryableTopic. Production: create topics via IaC instead. */
@Configuration
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaTopicsConfig {

    /** Creates the order-events topic if missing, with the configured partitions and replicas. */
    @Bean
    NewTopic orderEventsTopic(@Value("${app.kafka.partitions:6}") int partitions,
                              @Value("${app.kafka.replicas:1}") int replicas) {
        return TopicBuilder.name(Topics.ORDER_EVENTS).partitions(partitions).replicas(replicas).build();
    }

    /** Creates the client-telemetry topic if missing, with the configured partitions and replicas. */
    @Bean
    NewTopic clientTelemetryTopic(@Value("${app.kafka.partitions:6}") int partitions,
                                  @Value("${app.kafka.replicas:1}") int replicas) {
        return TopicBuilder.name(Topics.CLIENT_TELEMETRY).partitions(partitions).replicas(replicas).build();
    }
}
