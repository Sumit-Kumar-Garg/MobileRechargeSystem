package com.app.recharge.provisioning;

import com.app.recharge.order.OrderPaidEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Starts provisioning right after the payment transaction commits. The worker is the safety net. */
@Component
public class PaidEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaidEventListener.class);

    private final ProvisioningService provisioning;

    public PaidEventListener(ProvisioningService provisioning) {
        this.provisioning = provisioning;
    }

    /**
     * After the payment transaction commits, makes one provisioning attempt on the provisioning thread pool. Errors are
     * logged; the worker retries.
     */
    @Async("provisioningExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaid(OrderPaidEvent event) {
        try {
            provisioning.provisionOnce(event.orderId());
        } catch (RuntimeException e) {
            log.error("Immediate provisioning failed for order {}; the worker will retry", event.orderId(), e);
        }
    }
}
