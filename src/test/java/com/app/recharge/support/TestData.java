package com.app.recharge.support;

import com.app.recharge.catalog.PlanView;
import com.app.recharge.number.SubscriberNumber;
import com.app.recharge.order.OrderEventPayload;
import com.app.recharge.order.RechargeOrder;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TestData {

    private TestData() { }

    public static PlanView plan(String code, int priceMinor, Integer dataMb) {
        return new PlanView(UUID.randomUUID(), code, "DE", "Prepaid " + code,
                new PlanView.Price(priceMinor, "EUR"), 28,
                new PlanView.Allowances(dataMb, 100, 100), List.of());
    }

    public static SubscriberNumber number(UUID userId) {
        return new SubscriberNumber(userId, new byte[] {1}, "hash", "+49 *** ***4567", "DE", Instant.now());
    }

    public static RechargeOrder order(UUID userId, PlanView plan, Instant now) {
        return RechargeOrder.create(userId, UUID.randomUUID(), "+49 *** ***4567", plan,
                "idem-key-idem-key-1", "fingerprint", null, now, Duration.ofMinutes(15));
    }

    public static OrderEventPayload event(String type, UUID userId) {
        return new OrderEventPayload(UUID.randomUUID(), 1, type, Instant.parse("2026-11-07T10:00:00Z"),
                UUID.randomUUID(), userId, UUID.randomUUID(), "+49 *** ***4567", 1500, "EUR", "Prepaid M", 28);
    }
}
