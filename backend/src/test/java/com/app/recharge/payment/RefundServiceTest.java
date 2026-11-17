package com.app.recharge.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.recharge.common.RechargeMetrics;
import com.app.recharge.order.OrderEvent;
import com.app.recharge.order.OrderEventRepository;
import com.app.recharge.order.OrderStore;
import com.app.recharge.order.OrderStatus;
import com.app.recharge.order.OrderTransitions;
import com.app.recharge.order.RechargeOrder;
import com.app.recharge.outbox.OutboxWriter;
import com.app.recharge.payment.spi.PspRefundRequest;
import com.app.recharge.payment.spi.PspResult;
import com.app.recharge.payment.spi.PspUnavailableException;
import com.app.recharge.support.MutableClock;
import com.app.recharge.support.NoOpTransactionManager;
import com.app.recharge.support.TestData;
import com.app.recharge.telemetry.JourneyRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefundServiceTest {

    @Mock OrderStore orders;
    @Mock PaymentRepository payments;
    @Mock RefundRepository refunds;
    @Mock OrderEventRepository eventRepo;
    @Mock OutboxWriter outbox;
    @Mock JourneyRecorder journey;
    @Mock PspGateway psp;

    final MutableClock clock = new MutableClock(Instant.parse("2026-11-07T10:00:00Z"));
    final AtomicReference<Refund> savedRefund = new AtomicReference<>();
    RefundService service;
    RechargeOrder order;
    Payment payment;

    @BeforeEach
    void setUp() {
        RechargeMetrics metrics = new RechargeMetrics(new SimpleMeterRegistry());
        OrderTransitions transitions = new OrderTransitions(eventRepo, outbox, journey, metrics, clock);
        service = new RefundService(orders, payments, refunds, transitions, psp,
                new TransactionTemplate(new NoOpTransactionManager()), metrics, clock);

        order = TestData.order(UUID.randomUUID(), TestData.plan("M-28", 1500, 10000), clock.instant());
        for (OrderStatus s : new OrderStatus[] {OrderStatus.PAYMENT_PENDING, OrderStatus.PAID,
                OrderStatus.PROVISIONING, OrderStatus.PROVISIONING_FAILED, OrderStatus.REFUND_INITIATED}) {
            order.transitionTo(s, clock.instant());
        }
        payment = Payment.create(order, 1, "mockpay", "card", clock.instant());
        payment.markSucceeded("psp_1", clock.instant());

        when(orders.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(refunds.findByOrderId(order.getId())).thenAnswer(i -> Optional.ofNullable(savedRefund.get()));
        when(refunds.findById(any(UUID.class))).thenAnswer(i -> Optional.ofNullable(savedRefund.get()));
        when(refunds.save(any(Refund.class))).thenAnswer(i -> {
            savedRefund.set(i.getArgument(0));
            return i.getArgument(0);
        });
        when(payments.findFirstByOrderIdAndStatusOrderByAttemptNoDesc(order.getId(), PaymentStatus.SUCCEEDED))
                .thenReturn(Optional.of(payment));
        when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(eventRepo.save(any(OrderEvent.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void refundsTheSuccessfulPaymentAndMarksTheOrderRefunded() {
        when(psp.refund(any(), any())).thenReturn(PspResult.succeeded("psp_refund_1"));

        service.refund(order.getId());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(savedRefund.get().getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        var captor = org.mockito.ArgumentCaptor.forClass(PspRefundRequest.class);
        verify(psp).refund(eq("mockpay"), captor.capture());
        assertThat(captor.getValue().amountMinor()).isEqualTo(1500);
        assertThat(captor.getValue().originalMerchantRef()).isEqualTo(payment.getId().toString());
        verify(outbox).write(any(), eq(order.getId().toString()), eq("OrderRefunded"), any());
    }

    @Test
    void pspOutageKeepsTheRefundPendingForTheWorker() {
        when(psp.refund(any(), any())).thenThrow(new PspUnavailableException("down"));

        service.refund(order.getId());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUND_INITIATED);
        assertThat(savedRefund.get().getStatus()).isEqualTo(RefundStatus.PENDING);
    }

    @Test
    void retryReusesTheSameRefundRecordSoThePspCallStaysIdempotent() {
        when(psp.refund(any(), any())).thenThrow(new PspUnavailableException("down"))
                .thenReturn(PspResult.succeeded("psp_refund_2"));

        service.refund(order.getId());
        UUID firstRefundId = savedRefund.get().getId();
        service.refund(order.getId());

        assertThat(savedRefund.get().getId()).isEqualTo(firstRefundId);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    void declinedRefundIsNotMarkedAsRefunded() {
        when(psp.refund(any(), any())).thenReturn(PspResult.declined("refund_failed"));

        service.refund(order.getId());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUND_INITIATED);
    }

    @Test
    void ordersNotAwaitingARefundAreIgnored() {
        RechargeOrder other = TestData.order(UUID.randomUUID(), TestData.plan("S-28", 1000, 3000), clock.instant());
        when(orders.findByIdForUpdate(other.getId())).thenReturn(Optional.of(other));

        service.refund(other.getId());

        verify(psp, never()).refund(any(), any());
    }
}
