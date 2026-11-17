package com.app.recharge.payment;

import com.app.recharge.common.RechargeMetrics;
import com.app.recharge.order.OrderStore;
import com.app.recharge.order.OrderStatus;
import com.app.recharge.order.OrderTransitions;
import com.app.recharge.order.RechargeOrder;
import com.app.recharge.payment.spi.PspRefundRequest;
import com.app.recharge.payment.spi.PspResult;
import com.app.recharge.payment.spi.PspUnavailableException;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Compensation step of the saga: money was taken but the recharge could not be delivered. */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private record RefundPrep(UUID refundId, UUID paymentId, String provider, long amountMinor, String currency) { }

    private final OrderStore orders;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final OrderTransitions transitions;
    private final PspGateway psp;
    private final TransactionTemplate tx;
    private final RechargeMetrics metrics;
    private final Clock clock;

    public RefundService(OrderStore orders, PaymentRepository payments, RefundRepository refunds,
                         OrderTransitions transitions, PspGateway psp, TransactionTemplate tx,
                         RechargeMetrics metrics, Clock clock) {
        this.orders = orders;
        this.payments = payments;
        this.refunds = refunds;
        this.transitions = transitions;
        this.psp = psp;
        this.tx = tx;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Safe to call repeatedly: the PSP call is idempotent per refund id and each step re-checks state. */
    public void refund(UUID orderId) {
        RefundPrep prep = tx.execute(s -> prepare(orderId));
        if (prep == null) {
            return;
        }
        PspResult result;
        try {
            result = psp.refund(prep.provider(), new PspRefundRequest(
                    prep.refundId().toString(), prep.paymentId().toString(), prep.amountMinor(), prep.currency()));
        } catch (PspUnavailableException e) {
            log.warn("Refund for order {} deferred, PSP unavailable", orderId);
            metrics.provisioning("refund_deferred");
            return;
        }
        if (result.status() != PspResult.Status.SUCCEEDED) {
            log.error("PSP declined refund {} for order {}", prep.refundId(), orderId);
            metrics.provisioning("refund_declined");
            return;
        }
        PspResult confirmed = result;
        tx.executeWithoutResult(s -> {
            Refund refund = refunds.findById(prep.refundId()).orElseThrow();
            refund.markSucceeded(confirmed.pspRef(), clock.instant());
            RechargeOrder order = orders.findByIdForUpdate(orderId).orElseThrow();
            if (order.getStatus() == OrderStatus.REFUND_INITIATED) {
                transitions.apply(order, OrderStatus.REFUNDED, "refunded");
            }
        });
        metrics.provisioning("refunded");
    }

    /**
     * In a transaction: checks the order is REFUND_INITIATED, creates the refund record on first call (for the latest
     * successful payment), and returns what the PSP call needs. Returns null when there is nothing to do; finishes the
     * order if the refund had already succeeded.
     */
    private RefundPrep prepare(UUID orderId) {
        RechargeOrder order = orders.findByIdForUpdate(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.REFUND_INITIATED) {
            return null;
        }
        Refund refund = refunds.findByOrderId(orderId).orElse(null);
        if (refund != null && refund.getStatus() == RefundStatus.SUCCEEDED) {
            transitions.apply(order, OrderStatus.REFUNDED, "refund already succeeded");
            return null;
        }
        if (refund == null) {
            Payment payment = payments.findFirstByOrderIdAndStatusOrderByAttemptNoDesc(orderId, PaymentStatus.SUCCEEDED)
                    .orElseThrow(() -> new IllegalStateException("No successful payment to refund for order " + orderId));
            refund = refunds.save(Refund.create(order, payment, clock.instant()));
        }
        // The refund goes to the provider that took the money.
        UUID refundId = refund.getId();
        UUID paymentId = refund.getPaymentId();
        String provider = payments.findById(paymentId).map(Payment::getProvider)
                .orElseThrow(() -> new IllegalStateException("Payment of refund " + refundId + " not found"));
        return new RefundPrep(refundId, paymentId, provider, refund.getAmountMinor(), refund.getCurrency());
    }
}
