package com.app.recharge.outbox;

import com.app.recharge.common.JobLease;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Relays committed outbox rows to Kafka.
 * - Exactly one instance publishes at a time (database lease), so events of one order keep their order even
 *   when several instances run. If the publisher dies, the lease expires and another instance continues.
 * - No database transaction or row lock is held while talking to Kafka: each row is marked in its own short
 *   update after the broker acknowledged it.
 * - At-least-once: a crash between the ack and the update re-sends the row; consumers are idempotent.
 * - Stops at the first failure so later events never overtake an earlier one.
 */
@Component
@ConditionalOnExpression("${app.kafka.enabled:true} && ${app.outbox.publisher.enabled:true}")
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    static final String LEASE_NAME = "outbox-publisher";
    static final Duration LEASE = Duration.ofSeconds(60);
    static final Duration RENEW_AFTER = Duration.ofSeconds(10);
    static final long SEND_TIMEOUT_SECONDS = 5;

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final JobLease lease;
    private final Clock clock;

    public OutboxPublisher(OutboxRepository repository, KafkaTemplate<String, String> kafka, JobLease lease, Clock clock) {
        this.repository = repository;
        this.kafka = kafka;
        this.lease = lease;
        this.clock = clock;
    }

    /** Scheduled entry point: publishes one batch and logs, rather than propagates, any error. */
    @Scheduled(fixedDelayString = "${app.outbox.publisher.delay:PT1S}")
    public void tick() {
        try {
            publishBatch();
        } catch (RuntimeException e) {
            log.error("Outbox publish tick failed", e);
        }
    }

    /** @return number of events published in this batch */
    public int publishBatch() {
        if (!lease.tryAcquire(LEASE_NAME, LEASE)) {
            return 0;                                        // another instance is the publisher right now
        }
        Instant leaseTakenAt = clock.instant();
        List<OutboxEvent> batch = repository.findTop100ByPublishedAtIsNullOrderByIdAsc();
        int count = 0;
        for (OutboxEvent event : batch) {
            if (Duration.between(leaseTakenAt, clock.instant()).compareTo(RENEW_AFTER) > 0) {
                if (!lease.tryAcquire(LEASE_NAME, LEASE)) {
                    break;                                   // lost the lease: stop, the new holder continues
                }
                leaseTakenAt = clock.instant();
            }
            try {
                kafka.send(event.getTopic(), event.getEventKey(), event.getPayload())
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                repository.recordFailure(event.getId());
                break;
            } catch (Exception e) {
                log.warn("Kafka publish failed for outbox event {} (attempt {})", event.getId(), event.getAttempts() + 1, e);
                repository.recordFailure(event.getId());
                break;
            }
            repository.markPublished(event.getId(), clock.instant());
            count++;
        }
        return count;
    }
}
