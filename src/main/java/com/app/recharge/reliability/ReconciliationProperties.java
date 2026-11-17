package com.app.recharge.reliability;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Reconciliation timings (app.reconciliation.*). The run interval itself is app.reconciliation.delay (ISO-8601,
 * read by the scheduler).
 *
 * @param pendingMinAge  a payment with no outcome is first checked with the provider after this age
 * @param notFoundGrace  how long a charge the provider does not know about may still be in flight. The payment is
 *                       voided and declined only after this; shorter values risk a double charge
 * @param lease          lease held by the instance running reconciliation
 */
@ConfigurationProperties("app.reconciliation")
public record ReconciliationProperties(
        @DefaultValue("60s") Duration pendingMinAge,
        @DefaultValue("10m") Duration notFoundGrace,
        @DefaultValue("5m") Duration lease) {

    public ReconciliationProperties {
        for (Duration d : new Duration[] {pendingMinAge, notFoundGrace, lease}) {
            if (d.isZero() || d.isNegative()) {
                throw new IllegalArgumentException("app.reconciliation.* durations must be positive");
            }
        }
    }

    /** The original defaults, for tests. */
    public static ReconciliationProperties defaults() {
        return new ReconciliationProperties(Duration.ofSeconds(60), Duration.ofMinutes(10), Duration.ofMinutes(5));
    }
}
