package com.app.recharge.provisioning;

import com.app.recharge.payment.RefundService;
import com.app.recharge.provisioning.spi.OcsUnavailableException;
import com.app.recharge.provisioning.spi.OcsResult;
import com.app.recharge.provisioning.spi.OcsRequest;
import com.app.recharge.common.RechargeMetrics;
import com.app.recharge.number.NumberService;
import com.app.recharge.order.OrderStore;
import com.app.recharge.order.OrderStatus;
import com.app.recharge.order.OrderTransitions;
import com.app.recharge.order.RechargeOrder;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers the recharge after payment. Each call performs at most one attempt and is safe to repeat.
 * Locks are held only while changing state, never during the OCS call.
 * "Unavailable" is an unknown outcome and is retried; once the attempt budget is spent the order is refunded.
 * A definitive rejection cannot be fixed by retrying, so it goes straight to a refund.
 * Before any refund the reference is cancelled at the billing system, so a recharge that went through without
 * us learning about it is reversed instead of being given away on top of the refund.
 */
@Service
public class ProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningService.class);

    private record Prepared(boolean refund, String provider, UUID numberId, String planCode, int validityDays) { }

    private final OrderStore orders;
    private final OrderTransitions transitions;
    private final NumberService numbers;
    private final OcsGateway ocs;
    private final RefundService refunds;
    private final TransactionTemplate tx;
    private final ProvisioningProperties props;
    private final RechargeMetrics metrics;
    private final Clock clock;

    public ProvisioningService(OrderStore orders, OrderTransitions transitions, NumberService numbers,
                               OcsGateway ocs, RefundService refunds, TransactionTemplate tx,
                               ProvisioningProperties props, RechargeMetrics metrics, Clock clock) {
        this.orders = orders;
        this.transitions = transitions;
        this.numbers = numbers;
        this.ocs = ocs;
        this.refunds = refunds;
        this.tx = tx;
        this.props = props;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Makes at most one attempt to activate a paid order: locks and marks it PROVISIONING, calls the fulfilment system
     * with no transaction open, then records COMPLETED or PROVISIONING_FAILED. A rejection or a spent attempt budget
     * leads straight to compensation (cancel and refund). Safe to call repeatedly.
     */
    public void provisionOnce(UUID orderId) {
        Prepared prep = tx.execute(s -> prepare(orderId));
        if (prep == null) {
            return;
        }
        if (prep.refund()) {
            compensate(orderId);
            return;
        }

        OcsResult result = null;
        String failure = null;
        try {
            String msisdn = numbers.plainMsisdn(prep.numberId());
            result = ocs.provision(prep.provider(), new OcsRequest(orderId.toString(), msisdn, prep.planCode(), prep.validityDays()));
        } catch (OcsUnavailableException e) {
            failure = "ocs_unavailable";
        }
        OcsResult finalResult = result;
        String finalFailure = failure;
        tx.executeWithoutResult(s -> finish(orderId, finalResult, finalFailure));

        RechargeOrder after = orders.findById(orderId).orElse(null);
        if (after == null) {
            return;
        }
        if (after.getStatus() == OrderStatus.REFUND_INITIATED) {
            compensate(orderId);                    // definitive rejection
        } else if (after.getStatus() == OrderStatus.PROVISIONING_FAILED
                && after.getProvisioningAttempts() >= props.maxAttempts()) {
            provisionOnce(orderId);                 // attempt budget spent: move on to the refund now
        }
    }

    /**
     * Cancel at the billing system, then refund. Safe to repeat. If the cancellation cannot be confirmed the
     * customer is still refunded (they must never be left paid and unserved) and the case is counted for
     * manual review, because the plan might be active without payment.
     */
    public void compensate(UUID orderId) {
        String provider = orders.findById(orderId).map(RechargeOrder::getPlanProvider).orElse(null);
        try {
            if (provider != null) {
                ocs.cancel(provider, orderId.toString());     // the system that would have activated the plan
            }
        } catch (OcsUnavailableException e) {
            metrics.provisioning("cancel_unconfirmed");
            log.warn("Could not confirm cancellation of order {} at the billing system before refunding", orderId);
        }
        refunds.refund(orderId);
    }

    /**
     * In a transaction: decides what to do for the order. Marks attempts stuck in PROVISIONING too long as failed;
     * starts the refund when attempts are used up; otherwise moves PAID or PROVISIONING_FAILED orders to PROVISIONING
     * and returns what the call needs. Returns null when there is nothing to do.
     */
    private Prepared prepare(UUID orderId) {
        RechargeOrder order = orders.findByIdForUpdate(orderId).orElse(null);
        if (order == null) {
            return null;
        }
        OrderStatus status = order.getStatus();
        Instant now = clock.instant();

        if (status == OrderStatus.PROVISIONING) {
            if (order.getUpdatedAt().isBefore(now.minus(props.staleAfter()))) {
                transitions.apply(order, OrderStatus.PROVISIONING_FAILED, "stale provisioning attempt");
            }
            return null;
        }
        if (status == OrderStatus.PROVISIONING_FAILED && order.getProvisioningAttempts() >= props.maxAttempts()) {
            transitions.apply(order, OrderStatus.REFUND_INITIATED, "provisioning attempts exhausted");
            return new Prepared(true, order.getPlanProvider(), null, null, 0);
        }
        if (status != OrderStatus.PAID && status != OrderStatus.PROVISIONING_FAILED) {
            return null;
        }
        order.incrementProvisioningAttempts();
        transitions.apply(order, OrderStatus.PROVISIONING, "attempt " + order.getProvisioningAttempts());
        return new Prepared(false, order.getPlanProvider(), order.getNumberId(), order.getPlanCode(), order.getValidityDays());
    }

    /**
     * In a transaction: records the result of the attempt if the order is still PROVISIONING. Success completes the
     * order; a rejection fails it and starts the refund; an unknown outcome leaves it PROVISIONING_FAILED for a retry.
     */
    private void finish(UUID orderId, OcsResult result, String failure) {
        RechargeOrder order = orders.findByIdForUpdate(orderId).orElseThrow();
        if (order.getStatus() != OrderStatus.PROVISIONING) {
            return;
        }
        if (result != null && result.status() == OcsResult.Status.SUCCESS) {
            transitions.apply(order, OrderStatus.COMPLETED, "provisioned");
            metrics.provisioning("success");
        } else {
            String code = result != null ? result.failureCode() : failure;
            transitions.applyFailure(order, OrderStatus.PROVISIONING_FAILED, code, code);
            if (result != null) {
                // The billing system said no. Another attempt would get the same answer.
                transitions.apply(order, OrderStatus.REFUND_INITIATED, "rejected by the billing system");
                metrics.provisioning("rejected");
            } else {
                metrics.provisioning("failed");
            }
        }
    }
}
