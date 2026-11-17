package com.app.recharge.order;

import com.app.recharge.catalog.PlanView;
import com.app.recharge.number.SubscriberNumber;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Separate bean so the insert runs in its own transaction: if the unique constraint trips on a concurrent
 * duplicate, the caller can still read the winner afterwards (a failed Postgres transaction cannot be reused).
 */
@Component
public class OrderCreator {

    private final OrderRepository orders;
    private final OrderTransitions transitions;
    private final OrderProperties props;
    private final Clock clock;

    public OrderCreator(OrderRepository orders, OrderTransitions transitions, OrderProperties props, Clock clock) {
        this.orders = orders;
        this.transitions = transitions;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Inserts the new order and its creation event in a new transaction and flushes at once, so a duplicate idempotency
     * key fails here and the caller can read the order that won.
     */
    @Transactional
    public RechargeOrder create(UUID userId, SubscriberNumber number, PlanView plan, String idempotencyKey,
                                String fingerprint, UUID journeyId) {
        RechargeOrder order = RechargeOrder.create(userId, number.getId(), number.getMsisdnMasked(), plan,
                idempotencyKey, fingerprint, journeyId, clock.instant(), props.ttl());
        orders.saveAndFlush(order);      // flush now so a duplicate key surfaces here
        transitions.created(order);
        return order;
    }
}
