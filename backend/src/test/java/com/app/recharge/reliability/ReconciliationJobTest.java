package com.app.recharge.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.recharge.common.JobLease;
import com.app.recharge.common.RechargeMetrics;
import com.app.recharge.order.OrderEvent;
import com.app.recharge.order.OrderEventRepository;
import com.app.recharge.order.OrderStore;
import com.app.recharge.order.OrderStatus;
import com.app.recharge.order.OrderTransitions;
import com.app.recharge.order.RechargeOrder;
import com.app.recharge.outbox.OutboxWriter;
import com.app.recharge.payment.Payment;
import com.app.recharge.payment.PendingPayment;
import com.app.recharge.payment.PaymentService;
import com.app.recharge.payment.PspGateway;
import com.app.recharge.payment.spi.PspResult;
import com.app.recharge.payment.spi.PspUnavailableException;
import com.app.recharge.support.MutableClock;
import com.app.recharge.support.NoOpTransactionManager;
import com.app.recharge.support.TestData;
import com.app.recharge.telemetry.JourneyRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
class ReconciliationJobTest {

    @Mock OrderStore orders;
    @Mock PaymentService paymentService;
    @Mock PspGateway psp;
    @Mock OrderEventRepository eventRepo;
    @Mock OutboxWriter outbox;
    @Mock JourneyRecorder journey;
    @Mock JobLease lease;

    final MutableClock clock = new MutableClock(Instant.parse("2026-11-07T10:00:00Z"));
    ReconciliationJob job;
    RechargeOrder order;
    Payment pending;

    @BeforeEach
    void setUp() {
        RechargeMetrics metrics = new RechargeMetrics(new SimpleMeterRegistry());
        OrderTransitions transitions = new OrderTransitions(eventRepo, outbox, journey, metrics, clock);
        job = new ReconciliationJob(orders, paymentService, psp, transitions,
                new TransactionTemplate(new NoOpTransactionManager()), lease, ReconciliationProperties.defaults(), clock);

        order = TestData.order(UUID.randomUUID(), TestData.plan("M-28", 1500, 10000), clock.instant());
        pending = Payment.create(order, 1, "mockpay", "card", clock.instant());
        when(paymentService.pendingOlderThan(any())).thenReturn(List.of(PendingPayment.from(pending)));
        when(eventRepo.save(any(OrderEvent.class))).thenAnswer(i -> i.getArgument(0));
        clock.advance(Duration.ofMinutes(2));
    }

    @Test
    void appliesWhatThePspSaysHappened() {
        PspResult approved = PspResult.succeeded("psp_7");
        when(psp.lookup("mockpay", pending.getId().toString())).thenReturn(Optional.of(approved));

        int resolved = job.reconcilePayments(Duration.ofSeconds(60));

        assertThat(resolved).isEqualTo(1);
        verify(paymentService).applyOutcome(pending.getId(), approved);
    }

    @Test
    void aChargeThePspDoesNotKnowYetIsLeftPendingBecauseItMayStillBeInFlight() {
        when(psp.lookup("mockpay", pending.getId().toString())).thenReturn(Optional.empty());      // payment is 2 minutes old

        int resolved = job.reconcilePayments(Duration.ofSeconds(60));

        assertThat(resolved).isZero();
        verify(psp, never()).cancel(any(), any());
        verify(paymentService, never()).applyOutcome(any(), any());
    }

    @Test
    void afterTheGracePeriodTheReferenceIsVoidedAtThePspBeforeTheCustomerMayRetry() {
        clock.advance(ReconciliationProperties.defaults().notFoundGrace());
        when(psp.lookup("mockpay", pending.getId().toString())).thenReturn(Optional.empty());
        when(psp.cancel("mockpay", pending.getId().toString())).thenReturn(PspResult.declined("cancelled"));

        job.reconcilePayments(Duration.ofSeconds(60));

        var captor = org.mockito.ArgumentCaptor.forClass(PspResult.class);
        verify(psp).cancel("mockpay", pending.getId().toString());
        verify(paymentService).applyOutcome(eq(pending.getId()), captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(PspResult.Status.DECLINED);
        assertThat(captor.getValue().failureCode()).isEqualTo("cancelled");
    }

    @Test
    void ifTheChargeLandedJustBeforeTheVoidTheOrderIsPaidNotDeclined() {
        clock.advance(ReconciliationProperties.defaults().notFoundGrace());
        PspResult approvedAfterAll = PspResult.succeeded("psp_late");
        when(psp.lookup("mockpay", pending.getId().toString())).thenReturn(Optional.empty());
        when(psp.cancel("mockpay", pending.getId().toString())).thenReturn(approvedAfterAll);

        job.reconcilePayments(Duration.ofSeconds(60));

        verify(paymentService).applyOutcome(pending.getId(), approvedAfterAll);
    }

    @Test
    void theScheduledRunDoesNothingWhenAnotherInstanceHoldsTheLease() {
        when(lease.tryAcquire(any(), any())).thenReturn(false);

        job.run();

        verify(psp, never()).lookup(any(), any());
    }

    @Test
    void pspOutagePostponesWithoutFailing() {
        when(psp.lookup(any(), any())).thenThrow(new PspUnavailableException("down"));

        int resolved = job.reconcilePayments(Duration.ofSeconds(60));

        assertThat(resolved).isZero();
        verify(paymentService, never()).applyOutcome(any(), any());
    }

    @Test
    void oneBrokenPaymentDoesNotStopTheRest() {
        Payment second = Payment.create(order, 2, "mockpay", "card", clock.instant());
        when(paymentService.pendingOlderThan(any()))
                .thenReturn(List.of(PendingPayment.from(pending), PendingPayment.from(second)));
        when(psp.lookup("mockpay", pending.getId().toString())).thenThrow(new IllegalStateException("bug"));
        when(psp.lookup("mockpay", second.getId().toString())).thenReturn(Optional.of(PspResult.succeeded("psp_8")));

        int resolved = job.reconcilePayments(Duration.ofSeconds(60));

        assertThat(resolved).isEqualTo(1);
        verify(paymentService).applyOutcome(eq(second.getId()), any());
    }

    @Test
    void expiresUnpaidOrdersPastTheirDeadline() {
        when(orders.expiredIn(any(), any())).thenReturn(List.of(order));
        when(orders.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        clock.advance(Duration.ofMinutes(20));

        assertThat(job.expireOrders()).isEqualTo(1);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED);
    }

    @Test
    void doesNotExpireAnOrderThatMovedOnInTheMeantime() {
        when(orders.expiredIn(any(), any())).thenReturn(List.of(order));
        order.transitionTo(OrderStatus.PAYMENT_PENDING, clock.instant());
        when(orders.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        clock.advance(Duration.ofMinutes(20));

        assertThat(job.expireOrders()).isZero();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    }
}
