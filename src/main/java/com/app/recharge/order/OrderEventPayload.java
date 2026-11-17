package com.app.recharge.order;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;

/**
 * Kafka contract for domain events. Versioned and additive-only. Deliberately PII-light:
 * only a masked number; real contact data is looked up at send time.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderEventPayload(
        UUID eventId,
        int schemaVersion,
        String type,
        Instant occurredAt,
        UUID orderId,
        UUID userId,
        UUID numberId,
        String msisdnMasked,
        long amountMinor,
        String currency,
        String planName,
        int validityDays) {

    /** Builds a schema-version-1 event of the given type from the order's current snapshot, with a new event id. */
    public static OrderEventPayload of(RechargeOrder o, String type, Instant now) {
        return new OrderEventPayload(UUID.randomUUID(), 1, type, now, o.getId(), o.getUserId(), o.getNumberId(),
                o.getMsisdnMasked(), o.getAmountMinor(), o.getCurrency(), o.getPlanName(), o.getValidityDays());
    }
}
