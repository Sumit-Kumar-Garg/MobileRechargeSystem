package com.app.recharge.order;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record OrderView(
        UUID id,
        String status,
        UUID numberId,
        UUID planId,
        String planProvider,
        String planCode,
        String planName,
        int amountMinor,
        String currency,
        int validityDays,
        String msisdnMasked,
        String failureCode,
        Instant expiresAt,
        Instant createdAt,
        Instant completedAt,
        Instant validUntil) {

    /**
     * Builds the API view of an order. {@code validUntil} is completion time plus the plan's validity, or null until
     * completed.
     */
    public static OrderView from(RechargeOrder o) {
        Instant validUntil = o.getCompletedAt() == null
                ? null
                : o.getCompletedAt().plus(Duration.ofDays(o.getValidityDays()));
        return new OrderView(o.getId(), o.getStatus().name(), o.getNumberId(), o.getPlanId(), o.getPlanProvider(), o.getPlanCode(),
                o.getPlanName(), o.getAmountMinor(), o.getCurrency(), o.getValidityDays(), o.getMsisdnMasked(),
                o.getFailureCode(), o.getExpiresAt(), o.getCreatedAt(), o.getCompletedAt(), validUntil);
    }
}
