package com.app.recharge.payment;

import com.app.recharge.payment.spi.PspUnavailableException;
import com.app.recharge.payment.spi.PspResult;
import com.app.recharge.payment.spi.PspChargeRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.recharge.common.BusinessException;
import com.app.recharge.common.ErrorCode;
import com.app.recharge.common.RechargeMetrics;
import com.app.recharge.order.OrderEvent;
import org.mockito.ArgumentMatchers;
import com.app.recharge.order.OrderEventRepository;
import com.app.recharge.order.OrderPaidEvent;
import com.app.recharge.order.OrderProperties;
import com.app.recharge.order.OrderStore;
import com.app.recharge.order.OrderStatus;
import com.app.recharge.order.OrderTransitions;
import com.app.recharge.order.OrderView;
import com.app.recharge.order.RechargeOrder;
import com.app.recharge.outbox.OutboxWriter;
import com.app.recharge.ratelimit.AbuseGuard;
import com.app.recharge.support.MutableClock;
import com.app.recharge.support.NoOpTransactionManager;
import com.app.recharge.support.TestData;
import com.app.recharge.telemetry.JourneyRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceTest {

    @Mock OrderStore orders;
    @Mock PaymentRepository payments;
    @Mock OrderEventRepository eventRepo;
    @Mock OutboxWriter outbox;
    @Mock JourneyRecorder journey;
    @Mock PspGateway psp;
    @Mock PaymentRouter router;
    @Mock ApplicationEventPublisher events;
    @Mock AbuseGuard abuse;

    final MutableClock clock = new MutableClock(Instant.parse("2026-11-07T10:00:00Z"));
    final UUID userId = UUID.randomUUID();
    final AtomicReference<Payment> savedPayment = new AtomicReference<>();
    PaymentService service;
    RechargeOrder order;

    @BeforeEach
    void setUp() {
        RechargeMetrics metrics = new RechargeMetrics(new SimpleMeterRegistry());
        OrderTransitions transitions = new OrderTransitions(eventRepo, outbox, journey, metrics, clock);
        service = new PaymentService(orders, payments, transitions, psp, router,
                new TransactionTemplate(new NoOpTransactionManager()), events, abuse,
                new OrderProperties(Duration.ofMinutes(15), 3), metrics, clock);

        order = TestData.order(userId, TestData.plan("M-28", 1500, 10000), clock.instant());
        when(orders.findByIdAndUserId(order.getId(), userId)).thenReturn(Optional.of(order));
        when(orders.findById(order.getId())).thenReturn(Optional.of(order));
        when(orders.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(payments.countByOrderId(order.getId())).thenReturn(0L);
        when(router.route(any(), any())).thenReturn("mockpay");
        when(payments.save(any(Payment.class))).thenAnswer(i -> {
            savedPayment.set(i.getArgument(0));
            return i.getArgument(0);
        });
        when(payments.findByIdForUpdate(any(UUID.class))).thenAnswer(i -> Optional.ofNullable(savedPayment.get()));
        when(payments.findOrderIdById(any(UUID.class)))
                .thenAnswer(i -> Optional.ofNullable(savedPayment.get()).map(Payment::getOrderId));
        when(eventRepo.save(any(OrderEvent.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void successfulPaymentMovesOrderToPaidAndTriggersProvisioning() {
        when(psp.charge(any(), any())).thenReturn(PspResult.succeeded("psp_1"));

        OrderView view = service.pay(userId, order.getId(), "tok_success");

        assertThat(view.status()).isEqualTo("PAID");
        assertThat(savedPayment.get().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(savedPayment.get().getPspRef()).isEqualTo("psp_1");
        verify(events).publishEvent(any(OrderPaidEvent.class));
        verify(outbox).write(ArgumentMatchers.eq("recharge.order.events.v1"), ArgumentMatchers.eq(order.getId().toString()),
                ArgumentMatchers.eq("OrderPaid"), any());
    }

    @Test
    void chargeUsesServerSideAmountAndPaymentIdAsIdempotencyKey() {
        when(psp.charge(any(), any())).thenReturn(PspResult.succeeded("psp_1"));

        service.pay(userId, order.getId(), "tok_success");

        var captor = org.mockito.ArgumentCaptor.forClass(PspChargeRequest.class);
        verify(psp).charge(eq("mockpay"), captor.capture());
        assertThat(captor.getValue().amountMinor()).isEqualTo(1500);
        assertThat(captor.getValue().currency()).isEqualTo("EUR");
        assertThat(captor.getValue().merchantRef()).isEqualTo(savedPayment.get().getId().toString());
    }

    @Test
    void theRouterPicksTheProviderAndItIsRecordedOnThePayment() {
        when(router.route("EUR", "wallet")).thenReturn("altpay");
        when(psp.charge(any(), any())).thenReturn(PspResult.succeeded("alt_1"));

        service.pay(userId, order.getId(), "tok_success", "wallet");

        verify(psp).charge(eq("altpay"), any());
        assertThat(savedPayment.get().getProvider()).isEqualTo("altpay");
        assertThat(savedPayment.get().getMethod()).isEqualTo("wallet");
    }

    @Test
    void aMissingMethodDefaultsToCard() {
        when(psp.charge(any(), any())).thenReturn(PspResult.succeeded("psp_1"));

        service.pay(userId, order.getId(), "tok_success");

        verify(router).route("EUR", "card");
    }

    @Test
    void anUnsupportedMethodIsRejectedBeforeAnythingIsWrittenOrCharged() {
        when(router.route(any(), any())).thenThrow(
                new BusinessException(ErrorCode.PAYMENT_METHOD_UNAVAILABLE, "not available"));

        assertThatThrownBy(() -> service.pay(userId, order.getId(), "tok_success", "crypto"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PAYMENT_METHOD_UNAVAILABLE));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(savedPayment.get()).isNull();
        verify(psp, never()).charge(any(), any());
    }

    @Test
    void declinedPaymentFailsOrderRecordsFailureAndDoesNotProvision() {
        when(psp.charge(any(), any())).thenReturn(PspResult.declined("card_declined"));

        OrderView view = service.pay(userId, order.getId(), "tok_decline");

        assertThat(view.status()).isEqualTo("PAYMENT_FAILED");
        assertThat(view.failureCode()).isEqualTo("card_declined");
        assertThat(savedPayment.get().getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(abuse).recordPaymentFailure(userId);
        verify(events, never()).publishEvent(any(OrderPaidEvent.class));
    }

    @Test
    void unknownPspOutcomeLeavesOrderPendingForReconciliation() {
        when(psp.charge(any(), any())).thenThrow(new PspUnavailableException("timeout"));

        OrderView view = service.pay(userId, order.getId(), "tok_timeout");

        assertThat(view.status()).isEqualTo("PAYMENT_PENDING");
        assertThat(savedPayment.get().getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(events, never()).publishEvent(any(OrderPaidEvent.class));
        verify(abuse, never()).recordPaymentFailure(any());
    }

    @Test
    void pendingOrderIsNeverChargedTwice() {
        order.transitionTo(OrderStatus.PAYMENT_PENDING, clock.instant());

        OrderView view = service.pay(userId, order.getId(), "tok_success");

        assertThat(view.status()).isEqualTo("PAYMENT_PENDING");
        verify(psp, never()).charge(any(), any());
    }

    @Test
    void alreadyCompletedOrderIsReturnedWithoutCharging() {
        order.transitionTo(OrderStatus.PAYMENT_PENDING, clock.instant());
        order.transitionTo(OrderStatus.PAID, clock.instant());

        assertThat(service.pay(userId, order.getId(), "tok_success").status()).isEqualTo("PAID");
        verify(psp, never()).charge(any(), any());
    }

    @Test
    void failedOrderCanBeRetried() {
        when(psp.charge(any(), any())).thenReturn(PspResult.declined("card_declined"), PspResult.succeeded("psp_2"));
        service.pay(userId, order.getId(), "tok_decline");
        when(payments.countByOrderId(order.getId())).thenReturn(1L);

        OrderView retry = service.pay(userId, order.getId(), "tok_success");

        assertThat(retry.status()).isEqualTo("PAID");
        assertThat(savedPayment.get().getAttemptNo()).isEqualTo(2);
    }

    @Test
    void attemptLimitPerOrderIsEnforced() {
        when(payments.countByOrderId(order.getId())).thenReturn(3L);

        assertThatThrownBy(() -> service.pay(userId, order.getId(), "tok_success"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PAYMENT_LIMIT_EXCEEDED));
        verify(psp, never()).charge(any(), any());
    }

    @Test
    void lockedOutUserIsStoppedBeforeAnyChargeAttempt() {
        doThrow(new BusinessException(ErrorCode.RATE_LIMITED, "locked")).when(abuse).assertPaymentAllowed(userId);

        assertThatThrownBy(() -> service.pay(userId, order.getId(), "tok_success"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.RATE_LIMITED));
        verify(psp, never()).charge(any(), any());
    }

    @Test
    void expiredOrderCannotBePaid() {
        clock.advance(Duration.ofMinutes(16));

        assertThatThrownBy(() -> service.pay(userId, order.getId(), "tok_success"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ORDER_STATE_INVALID));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        verify(psp, never()).charge(any(), any());
    }

    @Test
    void anotherUsersOrderLooksLikeNotFound() {
        UUID stranger = UUID.randomUUID();
        when(orders.findByIdAndUserId(order.getId(), stranger)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.pay(stranger, order.getId(), "tok_success"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void applyingTheSameOutcomeTwiceTriggersProvisioningOnce() {
        when(psp.charge(any(), any())).thenThrow(new PspUnavailableException("timeout"));
        service.pay(userId, order.getId(), "tok_timeout");
        UUID paymentId = savedPayment.get().getId();

        service.applyOutcome(paymentId, PspResult.succeeded("psp_9"));
        service.applyOutcome(paymentId, PspResult.succeeded("psp_9"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(events, times(1)).publishEvent(any(OrderPaidEvent.class));
    }

    @Test
    void theOutcomeIsAppliedUnderTheOrderLockAndThePaymentLockInThatOrder() {
        when(psp.charge(any(), any())).thenReturn(PspResult.succeeded("psp_1"));

        service.pay(userId, order.getId(), "tok_success");

        // order first, then payment, and the payment is only read once both rows are locked
        var inOrder = org.mockito.Mockito.inOrder(orders, payments);
        inOrder.verify(payments).findOrderIdById(savedPayment.get().getId());
        inOrder.verify(orders).findByIdForUpdate(order.getId());
        inOrder.verify(payments).findByIdForUpdate(savedPayment.get().getId());
        verify(payments, never()).findById(any(UUID.class));
    }

    @Test
    void aLateDeclineFromReconciliationCannotOverwriteASuccessThatWasAlreadyApplied() {
        when(psp.charge(any(), any())).thenReturn(PspResult.succeeded("psp_1"));
        service.pay(userId, order.getId(), "tok_success");

        service.applyOutcome(savedPayment.get().getId(), PspResult.declined("cancelled"));

        assertThat(savedPayment.get().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getFailureCode()).isNull();
    }

    @Test
    void ofTwoParallelPayRequestsTheOneThatLosesTheOrderLockDoesNotCharge() {
        // The first request passed its checks; before it gets the order lock, the other request opens the attempt.
        when(orders.findByIdForUpdate(order.getId())).thenAnswer(i -> {
            order.transitionTo(OrderStatus.PAYMENT_PENDING, clock.instant());
            return Optional.of(order);
        });

        OrderView view = service.pay(userId, order.getId(), "tok_success");

        assertThat(view.status()).isEqualTo("PAYMENT_PENDING");
        verify(payments, never()).save(any(Payment.class));
        verify(psp, never()).charge(any(), any());
    }

    @Test
    void aDeclineStoresTheFailureCodeOnTheOrderThroughTheTransition() {
        when(psp.charge(any(), any())).thenReturn(PspResult.declined("card_declined"));

        service.pay(userId, order.getId(), "tok_decline");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(order.getFailureCode()).isEqualTo("card_declined");
    }

    @Test
    void pendingPaymentsAreHandedToReconciliationWithoutExposingTheEntity() {
        Payment pending = Payment.create(order, 1, "altpay", "wallet", clock.instant());
        when(payments.findTop50ByStatusAndCreatedAtBefore(eq(PaymentStatus.PENDING), any()))
                .thenReturn(java.util.List.of(pending));

        var result = service.pendingOlderThan(clock.instant());

        assertThat(result).containsExactly(new PendingPayment(pending.getId(), "altpay", pending.getCreatedAt()));
    }
}
