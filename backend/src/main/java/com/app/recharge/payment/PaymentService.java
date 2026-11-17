package com.app.recharge.payment;

import com.app.recharge.common.BusinessException;
import com.app.recharge.common.ErrorCode;
import com.app.recharge.common.RechargeMetrics;
import com.app.recharge.order.OrderPaidEvent;
import com.app.recharge.order.OrderProperties;
import com.app.recharge.order.OrderStatus;
import com.app.recharge.order.OrderStore;
import com.app.recharge.order.OrderTransitions;
import com.app.recharge.order.OrderView;
import com.app.recharge.order.RechargeOrder;
import com.app.recharge.payment.spi.PspChargeRequest;
import com.app.recharge.payment.spi.PspResult;
import com.app.recharge.payment.spi.PspUnavailableException;
import com.app.recharge.ratelimit.AbuseGuard;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payment orchestration. The database is never held open during the PSP call:
 * (1) commit "payment pending", (2) call the PSP, (3) commit the outcome.
 * If the PSP outcome is unknown the order stays PAYMENT_PENDING and reconciliation resolves it.
 *
 * Locking rule for the whole module: lock the order row first, then the payment row. Every writer follows
 * the same order, so two writers can wait for each other but never deadlock.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private record PaymentAttempt(UUID paymentId, String provider, long amountMinor, String currency) { }

    private final OrderStore orders;
    private final PaymentRepository payments;
    private final OrderTransitions transitions;
    private final PspGateway psp;
    private final PaymentRouter router;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final AbuseGuard abuseGuard;
    private final OrderProperties props;
    private final RechargeMetrics metrics;
    private final Clock clock;

    public PaymentService(OrderStore orders, PaymentRepository payments, OrderTransitions transitions,
                          PspGateway psp, PaymentRouter router, TransactionTemplate tx,
                          ApplicationEventPublisher events,
                          AbuseGuard abuseGuard, OrderProperties props, RechargeMetrics metrics, Clock clock) {
        this.orders = orders;
        this.payments = payments;
        this.transitions = transitions;
        this.psp = psp;
        this.router = router;
        this.tx = tx;
        this.events = events;
        this.abuseGuard = abuseGuard;
        this.props = props;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Pays with the default method (card). See {@link #pay(UUID, UUID, String, String)}. */
    public OrderView pay(UUID userId, UUID orderId, String paymentToken) {
        return pay(userId, orderId, paymentToken, null);
    }

    /**
     * Pays one of the user's orders. Already paid or pending orders are returned unchanged (never charged twice);
     * expired orders are rejected. Checks abuse and attempt limits, picks a provider, opens an attempt, charges, and
     * returns the order as it now stands.
     */
    public OrderView pay(UUID userId, UUID orderId, String paymentToken, String requestedMethod) {
        String method = requestedMethod == null || requestedMethod.isBlank() ? PaymentRouter.DEFAULT_METHOD : requestedMethod;
        RechargeOrder order = orders.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found"));

        if (!isPayable(order.getStatus())) {
            if (order.getStatus() == OrderStatus.EXPIRED) {
                throw new BusinessException(ErrorCode.ORDER_STATE_INVALID, "Order has expired");
            }
            return OrderView.from(order);    // already paid / pending / done: never submit a second charge
        }
        rejectIfExpired(order);
        assertWithinLimits(userId, orderId);

        // Chosen before anything is written: unsupported method -> clean 422, nothing pending.
        String provider = router.route(order.getCurrency(), method);

        PaymentAttempt attempt = openAttempt(orderId, provider, method);
        if (attempt == null) {
            return reload(orderId);          // a parallel request got there first; it owns the charge
        }
        charge(attempt, paymentToken);
        return reload(orderId);
    }

    /** True for statuses that may start a payment attempt: CREATED or PAYMENT_FAILED. */
    private static boolean isPayable(OrderStatus status) {
        return status == OrderStatus.CREATED || status == OrderStatus.PAYMENT_FAILED;
    }

    /** An order past its deadline is closed here instead of waiting for the reconciliation job. */
    private void rejectIfExpired(RechargeOrder order) {
        if (!order.getExpiresAt().isBefore(clock.instant())) {
            return;
        }
        tx.executeWithoutResult(s -> orders.findByIdForUpdate(order.getId())
                .filter(o -> isPayable(o.getStatus()))
                .ifPresent(o -> transitions.apply(o, OrderStatus.EXPIRED, "expired before payment")));
        throw new BusinessException(ErrorCode.ORDER_STATE_INVALID, "Order has expired");
    }

    /**
     * Rejects the payment if the user is locked out after repeated failures, or the order has used up its payment
     * attempts.
     */
    private void assertWithinLimits(UUID userId, UUID orderId) {
        abuseGuard.assertPaymentAllowed(userId);
        if (payments.countByOrderId(orderId) >= props.maxPaymentAttempts()) {
            throw new BusinessException(ErrorCode.PAYMENT_LIMIT_EXCEEDED, "Too many payment attempts for this order");
        }
    }

    /**
     * Step 1: commits "payment pending". The order row is locked and its status checked again, so of two
     * parallel pay requests exactly one opens an attempt. Returns null for the one that lost.
     */
    private PaymentAttempt openAttempt(UUID orderId, String provider, String method) {
        return tx.execute(s -> {
            RechargeOrder order = orders.findByIdForUpdate(orderId).orElseThrow();
            if (!isPayable(order.getStatus())) {
                return null;
            }
            int attemptNo = (int) payments.countByOrderId(orderId) + 1;
            transitions.apply(order, OrderStatus.PAYMENT_PENDING, "payment attempt " + attemptNo);
            Payment payment = payments.save(Payment.create(order, attemptNo, provider, method, clock.instant()));
            return new PaymentAttempt(payment.getId(), provider, order.getAmountMinor(), order.getCurrency());
        });
    }

    /** Steps 2 and 3: call the provider with no transaction open, then commit what it answered. */
    private void charge(PaymentAttempt attempt, String paymentToken) {
        PspResult result;
        try {
            result = psp.charge(attempt.provider(), new PspChargeRequest(
                    attempt.paymentId().toString(), attempt.amountMinor(), attempt.currency(), paymentToken));
        } catch (PspUnavailableException e) {
            // Unknown is not failed: the order stays PAYMENT_PENDING and reconciliation finds out what happened.
            metrics.payment("unknown");
            log.warn("Outcome unknown for payment {} at provider {}: {}", attempt.paymentId(), attempt.provider(), e.getMessage());
            return;
        }
        applyOutcome(attempt.paymentId(), result);
    }

    /**
     * Applies a PSP result to a pending payment. Idempotent; shared by the pay flow and reconciliation.
     * Both rows are locked BEFORE the "still pending?" check, so when the pay flow and reconciliation arrive
     * together the second one sees the first one's committed result and does nothing.
     */
    public void applyOutcome(UUID paymentId, PspResult result) {
        tx.executeWithoutResult(s -> {
            UUID orderId = payments.findOrderIdById(paymentId).orElseThrow();
            RechargeOrder order = orders.findByIdForUpdate(orderId).orElseThrow();
            Payment payment = payments.findByIdForUpdate(paymentId).orElseThrow();
            if (payment.getStatus() != PaymentStatus.PENDING) {
                return;
            }
            Instant now = clock.instant();
            if (result.status() == PspResult.Status.SUCCEEDED) {
                payment.markSucceeded(result.pspRef(), now);
                if (order.getStatus() == OrderStatus.PAYMENT_PENDING) {
                    transitions.apply(order, OrderStatus.PAID, "payment succeeded");
                    events.publishEvent(new OrderPaidEvent(order.getId()));
                } else {
                    log.error("Payment {} succeeded but order {} is {}", paymentId, order.getId(), order.getStatus());
                }
                metrics.payment("succeeded");
            } else {
                payment.markFailed(result.failureCode(), now);
                if (order.getStatus() == OrderStatus.PAYMENT_PENDING) {
                    transitions.applyFailure(order, OrderStatus.PAYMENT_FAILED, result.failureCode(), "payment declined");
                }
                abuseGuard.recordPaymentFailure(order.getUserId());
                metrics.payment("declined");
            }
        });
    }

    /** Payments still without an outcome that were started before {@code createdBefore} (at most 50). */
    public List<PendingPayment> pendingOlderThan(Instant createdBefore) {
        return payments.findTop50ByStatusAndCreatedAtBefore(PaymentStatus.PENDING, createdBefore).stream()
                .map(PendingPayment::from)
                .toList();
    }

    /** Re-reads the order and returns its current view. */
    private OrderView reload(UUID orderId) {
        return OrderView.from(orders.findById(orderId).orElseThrow());
    }
}
