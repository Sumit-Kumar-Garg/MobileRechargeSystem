package com.app.recharge.order;

import com.app.recharge.common.RechargeMetrics;
import com.app.recharge.common.Topics;
import com.app.recharge.outbox.OutboxWriter;
import com.app.recharge.telemetry.JourneyRecorder;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The only place that changes an order's status. Every change atomically records the audit event, the outbox
 * domain event, the server-side journey event and a metric. Must be called inside a transaction.
 */
@Component
public class OrderTransitions {

    private static final Map<OrderStatus, String> DOMAIN_EVENTS = new EnumMap<>(OrderStatus.class);
    private static final Map<OrderStatus, String> JOURNEY_EVENTS = new EnumMap<>(OrderStatus.class);

    static {
        DOMAIN_EVENTS.put(OrderStatus.PAID, "OrderPaid");
        DOMAIN_EVENTS.put(OrderStatus.COMPLETED, "OrderCompleted");
        DOMAIN_EVENTS.put(OrderStatus.PAYMENT_FAILED, "OrderFailed");
        DOMAIN_EVENTS.put(OrderStatus.REFUNDED, "OrderRefunded");

        JOURNEY_EVENTS.put(OrderStatus.PAYMENT_PENDING, "payment_started");
        JOURNEY_EVENTS.put(OrderStatus.PAID, "payment_succeeded");
        JOURNEY_EVENTS.put(OrderStatus.PAYMENT_FAILED, "payment_failed");
        JOURNEY_EVENTS.put(OrderStatus.COMPLETED, "recharge_completed");
        JOURNEY_EVENTS.put(OrderStatus.PROVISIONING_FAILED, "provisioning_failed");
        JOURNEY_EVENTS.put(OrderStatus.REFUNDED, "refund_issued");
    }

    private final OrderEventRepository events;
    private final OutboxWriter outbox;
    private final JourneyRecorder journey;
    private final RechargeMetrics metrics;
    private final Clock clock;

    public OrderTransitions(OrderEventRepository events, OutboxWriter outbox, JourneyRecorder journey,
                            RechargeMetrics metrics, Clock clock) {
        this.events = events;
        this.outbox = outbox;
        this.journey = journey;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Records a newly created order: audit event, OrderCreated outbox event and metric. */
    public void created(RechargeOrder order) {
        Instant now = clock.instant();
        events.save(new OrderEvent(order.getId(), null, OrderStatus.CREATED, "order created", now));
        emit(order, "OrderCreated", now);
        metrics.orderTransition(OrderStatus.CREATED.name());
    }

    /**
     * Moves the order to {@code next} (throws if not allowed) and records the audit event, the domain event and journey
     * event for that status if there is one, and a metric.
     */
    public void apply(RechargeOrder order, OrderStatus next, String reason) {
        Instant now = clock.instant();
        OrderStatus from = order.getStatus();
        order.transitionTo(next, now);
        events.save(new OrderEvent(order.getId(), from, next, reason, now));

        String domainEvent = DOMAIN_EVENTS.get(next);
        if (domainEvent != null) {
            emit(order, domainEvent, now);
        }
        String journeyEvent = JOURNEY_EVENTS.get(next);
        if (journeyEvent != null) {
            journey.record(journeyEvent, order.getUserId(), order.getJourneyId(), order.getId(),
                    Map.of("plan", order.getPlanCode()));
        }
        metrics.orderTransition(next.name());
    }

    /** A transition into a failed state. The failure code is stored on the order as part of the same change. */
    public void applyFailure(RechargeOrder order, OrderStatus next, String failureCode, String reason) {
        order.recordFailure(failureCode);
        apply(order, next, reason);
    }

    /**
     * Writes a domain event for the order to the order-events outbox, keyed by order id so one order's events stay in
     * sequence.
     */
    private void emit(RechargeOrder order, String type, Instant now) {
        outbox.write(Topics.ORDER_EVENTS, order.getId().toString(), type, OrderEventPayload.of(order, type, now));
    }
}
