package com.app.recharge.payment;

import java.time.Instant;
import java.util.UUID;

/** What reconciliation needs to know about a payment whose outcome is still unknown. */
public record PendingPayment(UUID id, String provider, Instant createdAt) {

    /** Takes the fields reconciliation needs from a payment. */
    public static PendingPayment from(Payment payment) {
        return new PendingPayment(payment.getId(), payment.getProvider(), payment.getCreatedAt());
    }
}
