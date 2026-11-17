package com.app.recharge.order;

import static org.assertj.core.api.Assertions.assertThat;
import static com.app.recharge.order.OrderStatus.*;

import org.junit.jupiter.api.Test;

class OrderStatusTest {

    @Test
    void happyPathIsAllowed() {
        assertThat(CREATED.canMoveTo(PAYMENT_PENDING)).isTrue();
        assertThat(PAYMENT_PENDING.canMoveTo(PAID)).isTrue();
        assertThat(PAID.canMoveTo(PROVISIONING)).isTrue();
        assertThat(PROVISIONING.canMoveTo(COMPLETED)).isTrue();
    }

    @Test
    void failureAndCompensationPathsAreAllowed() {
        assertThat(PAYMENT_PENDING.canMoveTo(PAYMENT_FAILED)).isTrue();
        assertThat(PAYMENT_FAILED.canMoveTo(PAYMENT_PENDING)).isTrue();   // retry with another card
        assertThat(PROVISIONING.canMoveTo(PROVISIONING_FAILED)).isTrue();
        assertThat(PROVISIONING_FAILED.canMoveTo(PROVISIONING)).isTrue();  // retry
        assertThat(PROVISIONING_FAILED.canMoveTo(REFUND_INITIATED)).isTrue();
        assertThat(REFUND_INITIATED.canMoveTo(REFUNDED)).isTrue();
    }

    @Test
    void dangerousShortcutsAreRejected() {
        assertThat(CREATED.canMoveTo(PAID)).isFalse();                 // cannot skip payment
        assertThat(CREATED.canMoveTo(COMPLETED)).isFalse();
        assertThat(PAYMENT_PENDING.canMoveTo(EXPIRED)).isFalse();      // money may be in flight
        assertThat(PAID.canMoveTo(COMPLETED)).isFalse();               // must go through PROVISIONING
        assertThat(PAID.canMoveTo(REFUNDED)).isFalse();
        assertThat(COMPLETED.canMoveTo(REFUNDED)).isFalse();
    }

    @Test
    void terminalStatesHaveNoExit() {
        for (OrderStatus terminal : new OrderStatus[] {COMPLETED, REFUNDED, EXPIRED}) {
            assertThat(terminal.isTerminal()).isTrue();
            for (OrderStatus next : OrderStatus.values()) {
                assertThat(terminal.canMoveTo(next)).as(terminal + " -> " + next).isFalse();
            }
        }
        assertThat(PAID.isTerminal()).isFalse();
    }
}
