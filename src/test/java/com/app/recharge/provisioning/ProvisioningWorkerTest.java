package com.app.recharge.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.app.recharge.common.JobLease;
import com.app.recharge.order.OrderStatus;
import com.app.recharge.order.OrderStore;
import com.app.recharge.order.RechargeOrder;
import com.app.recharge.support.MutableClock;
import com.app.recharge.support.TestData;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProvisioningWorkerTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-07T10:00:00Z"));
    private final OrderStore orders = mock(OrderStore.class);
    private final ProvisioningService provisioning = mock(ProvisioningService.class);
    private final JobLease lease = mock(JobLease.class);
    private final ProvisioningWorker worker = new ProvisioningWorker(orders, provisioning,
            new ProvisioningProperties(3, Duration.ofMinutes(2), Duration.ofSeconds(5)), lease, clock);

    private RechargeOrder failedOrder(int attempts) {
        RechargeOrder o = TestData.order(UUID.randomUUID(), TestData.plan("M-28", 1500, 10000), clock.instant());
        o.transitionTo(OrderStatus.PAYMENT_PENDING, clock.instant());
        o.transitionTo(OrderStatus.PAID, clock.instant());
        for (int i = 0; i < attempts; i++) {
            o.incrementProvisioningAttempts();
        }
        o.transitionTo(OrderStatus.PROVISIONING, clock.instant());
        o.transitionTo(OrderStatus.PROVISIONING_FAILED, clock.instant());
        return o;
    }

    @Test
    void backoffDoublesWithEachAttempt() {
        RechargeOrder afterOne = failedOrder(1);      // waits 10s
        RechargeOrder afterThree = failedOrder(3);    // waits 40s

        clock.advance(Duration.ofSeconds(11));
        assertThat(worker.backoffElapsed(afterOne, clock.instant())).isTrue();
        assertThat(worker.backoffElapsed(afterThree, clock.instant())).isFalse();

        clock.advance(Duration.ofSeconds(30));
        assertThat(worker.backoffElapsed(afterThree, clock.instant())).isTrue();
    }

    @Test
    void sweepsOnlyOnTheInstanceHoldingTheLease() {
        when(lease.tryAcquire(any(), any())).thenReturn(false);
        worker.run();
        verifyNoInteractions(orders, provisioning);

        when(lease.tryAcquire(any(), any())).thenReturn(true);
        worker.run();
        verify(orders).oldestInStatus(OrderStatus.PAID, clock.instant().minusSeconds(3));
    }
}
