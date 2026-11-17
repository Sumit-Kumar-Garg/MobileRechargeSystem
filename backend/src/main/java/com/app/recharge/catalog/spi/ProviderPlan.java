package com.app.recharge.catalog.spi;

import java.util.List;

/**
 * A plan as supplied by a provider. Money is in minor units. Allowances are null when not metered.
 * {@code code} identifies the plan within the provider and market and must not change.
 */
public record ProviderPlan(
        String code,
        String market,
        String name,
        int priceMinor,
        String currency,
        int validityDays,
        Integer dataMb,
        Integer voiceMin,
        Integer sms,
        List<String> benefits) { }
