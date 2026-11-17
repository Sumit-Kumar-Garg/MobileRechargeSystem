package com.app.recharge.catalog;

import java.util.List;
import java.util.UUID;

/**
 * API representation of a plan. Immutable so it is safe to cache.
 * Allowances are null when the plan does not meter that item separately (see benefits for unlimited offers).
 * {@code provider} says who supplies the plan (the operator itself or a partner); fulfilment is routed there.
 * "Valid until" is intentionally not here: it depends on purchase time, so clients compute it from validityDays.
 */
public record PlanView(
        UUID id,
        String provider,
        String code,
        String market,
        String name,
        Price price,
        int validityDays,
        Allowances allowances,
        List<Benefit> benefits) {

    public static final String DEFAULT_PROVIDER = "operator";

    /** A plan of the operator itself (the built-in provider). */
    public PlanView(UUID id, String code, String market, String name, Price price, int validityDays,
                    Allowances allowances, List<Benefit> benefits) {
        this(id, DEFAULT_PROVIDER, code, market, name, price, validityDays, allowances, benefits);
    }

    public record Price(int amountMinor, String currency) { }

    public record Allowances(Integer dataMb, Integer voiceMin, Integer sms) { }

    public record Benefit(String type, String label) { }
}
