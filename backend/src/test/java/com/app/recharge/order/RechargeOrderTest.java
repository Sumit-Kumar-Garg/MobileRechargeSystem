package com.app.recharge.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.recharge.common.BusinessException;
import com.app.recharge.common.ErrorCode;
import com.app.recharge.support.TestData;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RechargeOrderTest {

    private final Instant now = Instant.parse("2026-11-07T10:00:00Z");

    @Test
    void snapshotsPlanAndSetsExpiry() {
        var plan = TestData.plan("M-28", 1500, 10000);
        RechargeOrder o = TestData.order(UUID.randomUUID(), plan, now);

        assertThat(o.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(o.getAmountMinor()).isEqualTo(1500);
        assertThat(o.getCurrency()).isEqualTo("EUR");
        assertThat(o.getPlanCode()).isEqualTo("M-28");
        assertThat(o.getExpiresAt()).isEqualTo(now.plus(Duration.ofMinutes(15)));
    }

    @Test
    void illegalTransitionIsRejectedAndStateIsUnchanged() {
        RechargeOrder o = TestData.order(UUID.randomUUID(), TestData.plan("S-28", 1000, 3000), now);

        assertThatThrownBy(() -> o.transitionTo(OrderStatus.COMPLETED, now))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ORDER_STATE_INVALID));
        assertThat(o.getStatus()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void completionStampsCompletedAt() {
        RechargeOrder o = TestData.order(UUID.randomUUID(), TestData.plan("S-28", 1000, 3000), now);
        o.transitionTo(OrderStatus.PAYMENT_PENDING, now);
        o.transitionTo(OrderStatus.PAID, now);
        o.transitionTo(OrderStatus.PROVISIONING, now);
        Instant done = now.plusSeconds(5);
        o.transitionTo(OrderStatus.COMPLETED, done);

        assertThat(o.getCompletedAt()).isEqualTo(done);
        assertThat(OrderView.from(o).validUntil()).isEqualTo(done.plus(Duration.ofDays(28)));
    }
}
