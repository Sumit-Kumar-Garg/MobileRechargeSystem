package com.app.recharge.provisioning;

import com.app.recharge.common.JobLease;
import com.app.recharge.order.OrderStore;
import com.app.recharge.order.OrderStatus;
import com.app.recharge.order.RechargeOrder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Safety net: picks up anything the immediate path missed (crash, rejected task, retries with backoff,
 * stale attempts, refunds waiting for the PSP). Exponential backoff per attempt.
 * One instance sweeps at a time (lease). Correctness does not depend on it: every order is locked and its
 * state re-checked before any work, so an overlapping sweep only repeats queries, never a recharge.
 */
@Component
@ConditionalOnProperty(name = "app.workers.enabled", havingValue = "true", matchIfMissing = true)
public class ProvisioningWorker {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningWorker.class);
    static final String LEASE_NAME = "provisioning-worker";
    /** Renewed on every sweep; if the holder dies, another instance takes over within this time. */
    static final Duration LEASE = Duration.ofMinutes(2);

    private final OrderStore orders;
    private final ProvisioningService provisioning;
    private final ProvisioningProperties props;
    private final JobLease lease;
    private final Clock clock;

    public ProvisioningWorker(OrderStore orders, ProvisioningService provisioning,
                              ProvisioningProperties props, JobLease lease, Clock clock) {
        this.orders = orders;
        this.provisioning = provisioning;
        this.props = props;
        this.lease = lease;
        this.clock = clock;
    }

    /**
     * Scheduled sweep (every 15 s by default): provisions paid orders the immediate path missed, retries failed
     * attempts whose backoff has passed, recovers attempts stuck in PROVISIONING, and retries pending refunds.
     * Does nothing on instances that do not hold the worker lease.
     */
    @Scheduled(fixedDelayString = "${app.provisioning.worker-delay:PT15S}")
    public void run() {
        if (!lease.tryAcquire(LEASE_NAME, LEASE)) {
            return;                 // another instance is the worker right now
        }
        Instant now = clock.instant();
        // newly paid orders the immediate path did not pick up
        orders.oldestInStatus(OrderStatus.PAID, now.minusSeconds(3))
                .forEach(o -> safely(o.getId(), provisioning::provisionOnce));
        // retries with exponential backoff
        orders.oldestInStatus(OrderStatus.PROVISIONING_FAILED, now.minus(props.baseBackoff()))
                .stream().filter(o -> backoffElapsed(o, now))
                .forEach(o -> safely(o.getId(), provisioning::provisionOnce));
        // attempts that died mid-flight
        orders.oldestInStatus(OrderStatus.PROVISIONING, now.minus(props.staleAfter()))
                .forEach(o -> safely(o.getId(), provisioning::provisionOnce));
        // refunds the PSP could not complete yet
        orders.oldestInStatus(OrderStatus.REFUND_INITIATED, now.minusSeconds(10))
                .forEach(o -> safely(o.getId(), provisioning::compensate));
    }

    /**
     * True when the order's retry wait has passed: base backoff doubled per attempt (capped at 64x), counted from its
     * last update.
     */
    boolean backoffElapsed(RechargeOrder order, Instant now) {
        long factor = 1L << Math.min(order.getProvisioningAttempts(), 6);
        Duration wait = props.baseBackoff().multipliedBy(factor);
        return order.getUpdatedAt().plus(wait).isBefore(now);
    }

    /** Runs the action for one order and logs any failure, so one bad order does not stop the sweep. */
    private void safely(UUID orderId, java.util.function.Consumer<UUID> action) {
        try {
            action.accept(orderId);
        } catch (RuntimeException e) {
            log.error("Worker failed for order {}", orderId, e);
        }
    }
}
