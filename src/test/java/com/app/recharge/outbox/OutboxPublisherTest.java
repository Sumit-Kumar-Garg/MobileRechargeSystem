package com.app.recharge.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.recharge.common.JobLease;
import com.app.recharge.support.MutableClock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxPublisherTest {

    @Mock OutboxRepository repository;
    @Mock KafkaTemplate<String, String> kafka;
    @Mock JobLease lease;

    final MutableClock clock = new MutableClock(Instant.parse("2026-11-07T10:00:00Z"));
    OutboxPublisher publisher;
    OutboxEvent first;
    OutboxEvent second;

    private static CompletableFuture<SendResult<String, String>> acked() {
        return CompletableFuture.completedFuture(null);
    }

    private static CompletableFuture<SendResult<String, String>> failed() {
        CompletableFuture<SendResult<String, String>> f = new CompletableFuture<>();
        f.completeExceptionally(new IllegalStateException("broker down"));
        return f;
    }

    @BeforeEach
    void setUp() {
        publisher = new OutboxPublisher(repository, kafka, lease, clock);
        first = new OutboxEvent("topic", "order-1", "OrderPaid", "{\"n\":1}", clock.instant());
        second = new OutboxEvent("topic", "order-2", "OrderCompleted", "{\"n\":2}", clock.instant());
        when(lease.tryAcquire(any(), any())).thenReturn(true);
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(first, second));
    }

    @Test
    void publishesInOrderAndMarksEachRowOnlyAfterTheBrokerAck() {
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(acked());

        assertThat(publisher.publishBatch()).isEqualTo(2);

        InOrder order = inOrder(kafka, repository);
        order.verify(kafka).send("topic", "order-1", "{\"n\":1}");
        order.verify(repository).markPublished(any(), eq(clock.instant()));
        order.verify(kafka).send("topic", "order-2", "{\"n\":2}");
        order.verify(repository).markPublished(any(), eq(clock.instant()));
    }

    @Test
    void stopsAtTheFirstFailureAndKeepsThatRowForRetry() {
        when(kafka.send(eq("topic"), eq("order-1"), anyString())).thenReturn(acked());
        when(kafka.send(eq("topic"), eq("order-2"), anyString())).thenReturn(failed());

        assertThat(publisher.publishBatch()).isEqualTo(1);

        verify(repository, times(1)).markPublished(any(), any());
        verify(repository, times(1)).recordFailure(any());
    }

    @Test
    void aFailureOnTheFirstRowSendsNothingElseToPreserveOrdering() {
        when(kafka.send(eq("topic"), eq("order-1"), anyString())).thenReturn(failed());

        assertThat(publisher.publishBatch()).isZero();

        verify(kafka, never()).send(eq("topic"), eq("order-2"), anyString());
        verify(repository, never()).markPublished(any(), any());
    }

    @Test
    void doesNothingWhenAnotherInstanceIsThePublisher() {
        when(lease.tryAcquire(any(), any())).thenReturn(false);

        assertThat(publisher.publishBatch()).isZero();

        verify(repository, never()).findTop100ByPublishedAtIsNullOrderByIdAsc();
        verify(kafka, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void emptyOutboxIsANoOp() {
        when(repository.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of());
        assertThat(publisher.publishBatch()).isZero();
    }

    @Test
    void tickNeverThrowsEvenIfTheDatabaseFails() {
        when(lease.tryAcquire(any(), any())).thenThrow(new IllegalStateException("db down"));
        publisher.tick();      // must not propagate: the scheduler thread has to survive
    }
}
