package com.app.recharge.reliability;

import com.app.recharge.common.JobLease;
import com.app.recharge.order.OrderStore;
import com.app.recharge.order.OrderStatus;
import com.app.recharge.order.OrderTransitions;
import com.app.recharge.payment.PaymentService;
import com.app.recharge.payment.PendingPayment;
import com.app.recharge.payment.PspGateway;
import com.app.recharge.payment.spi.PspResult;
import com.app.recharge.payment.spi.PspUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Closes the gaps that timeouts and crashes leave behind: payments whose outcome we never learned,
 * and orders nobody paid for. Never expires an order whose payment may still be in flight.
 */
@Component
@ConditionalOnProperty(name = "app.workers.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final OrderStore orders;
    private final PaymentService paymentService;
    private final PspGateway psp;
    private final OrderTransitions transitions;
    private final TransactionTemplate tx;
    private final JobLease lease;
    private final ReconciliationProperties props;
    private final Clock clock;

    public ReconciliationJob(OrderStore orders, PaymentService paymentService,
                             PspGateway psp, OrderTransitions transitions, TransactionTemplate tx, JobLease lease,
                             ReconciliationProperties props, Clock clock) {
        this.orders = orders;
        this.paymentService = paymentService;
        this.psp = psp;
        this.transitions = transitions;
        this.tx = tx;
        this.lease = lease;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Scheduled entry point (every 60 s by default), on one instance: resolves pending payments older than app.reconciliation.pending-min-age,
     * then expires unpaid orders.
     */
    @Scheduled(fixedDelayString = "${app.reconciliation.delay:PT60S}")
    public void run() {
        if (!lease.tryAcquire("reconciliation", props.lease())) {
            return;                 // another instance is doing this run
        }
        reconcilePayments(props.pendingMinAge());
        expireOrders();
    }

    /**
     * Resolves PENDING payments older than {@code minAge} by asking the PSP what actually happened.
     * If the PSP has no record, the charge may still be in flight: wait for app.reconciliation.not-found-grace, then
     * void the reference at the PSP before giving the order back to the customer. Voiding first means a late
     * charge can no longer succeed, so letting the customer retry cannot lead to a double charge.
     */
    public int reconcilePayments(Duration minAge) {
        Instant now = clock.instant();
        List<PendingPayment> pending = paymentService.pendingOlderThan(now.minus(minAge));
        int resolved = 0;
        for (PendingPayment payment : pending) {
            String merchantRef = payment.id().toString();
            try {
                Optional<PspResult> known = psp.lookup(payment.provider(), merchantRef);
                PspResult result;
                if (known.isPresent()) {
                    result = known.get();
                } else if (payment.createdAt().isAfter(now.minus(props.notFoundGrace()))) {
                    continue;                                   // too early to conclude anything
                } else {
                    result = psp.cancel(payment.provider(), merchantRef);           // final state: voided, or approved after all
                }
                paymentService.applyOutcome(payment.id(), result);
                resolved++;
            } catch (PspUnavailableException e) {
                log.warn("Reconciliation of payment {} postponed, PSP unavailable", payment.id());
            } catch (RuntimeException e) {
                log.error("Reconciliation failed for payment {}", payment.id(), e);
            }
        }
        return resolved;
    }

    /** Expires unpaid orders. PAYMENT_PENDING is excluded on purpose: money may be in flight. */
    public int expireOrders() {
        Instant now = clock.instant();
        var expired = orders.expiredIn(
                List.of(OrderStatus.CREATED, OrderStatus.PAYMENT_FAILED), now);
        int count = 0;
        for (var candidate : expired) {
            try {
                Boolean done = tx.execute(s -> orders.findByIdForUpdate(candidate.getId())
                        .filter(o -> o.getStatus() == OrderStatus.CREATED || o.getStatus() == OrderStatus.PAYMENT_FAILED)
                        .map(o -> {
                            transitions.apply(o, OrderStatus.EXPIRED, "order expired");
                            return true;
                        }).orElse(false));
                if (Boolean.TRUE.equals(done)) {
                    count++;
                }
            } catch (RuntimeException e) {
                log.error("Expiry failed for order {}", candidate.getId(), e);
            }
        }
        return count;
    }
}
