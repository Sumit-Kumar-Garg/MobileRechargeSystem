package com.app.recharge.order;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Single source of truth for which order state changes are legal. */
public enum OrderStatus {
    CREATED, PAYMENT_PENDING, PAYMENT_FAILED, PAID, PROVISIONING, PROVISIONING_FAILED,
    COMPLETED, REFUND_INITIATED, REFUNDED, EXPIRED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = new EnumMap<>(OrderStatus.class);

    static {
        ALLOWED.put(CREATED, EnumSet.of(PAYMENT_PENDING, EXPIRED));
        ALLOWED.put(PAYMENT_PENDING, EnumSet.of(PAID, PAYMENT_FAILED));
        ALLOWED.put(PAYMENT_FAILED, EnumSet.of(PAYMENT_PENDING, EXPIRED));
        ALLOWED.put(PAID, EnumSet.of(PROVISIONING));
        ALLOWED.put(PROVISIONING, EnumSet.of(COMPLETED, PROVISIONING_FAILED));
        ALLOWED.put(PROVISIONING_FAILED, EnumSet.of(PROVISIONING, REFUND_INITIATED));
        ALLOWED.put(REFUND_INITIATED, EnumSet.of(REFUNDED));
    }

    /** True if moving from this status to {@code next} is allowed by the state machine. */
    public boolean canMoveTo(OrderStatus next) {
        return ALLOWED.getOrDefault(this, EnumSet.noneOf(OrderStatus.class)).contains(next);
    }

    /** True for final states (COMPLETED, REFUNDED, EXPIRED) that never change again. */
    public boolean isTerminal() {
        return this == COMPLETED || this == REFUNDED || this == EXPIRED;
    }
}
