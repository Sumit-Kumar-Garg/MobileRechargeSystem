package com.app.recharge.observability;

import com.app.recharge.order.OrderStore;
import com.app.recharge.order.OrderStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * "Orders stuck in a non-terminal money-related state for over 5 minutes". The single most important
 * alert for this service: anything above zero for long means customers paid and did not get their recharge.
 * Every instance reports the same database count, so dashboards and alerts must aggregate it with
 * {@code max}, never {@code sum} (which would multiply it by the number of instances).
 */
@Component
public class StuckOrdersGauge {

    static final List<OrderStatus> WATCHED = List.of(
            OrderStatus.PAYMENT_PENDING, OrderStatus.PAID, OrderStatus.PROVISIONING, OrderStatus.REFUND_INITIATED);

    /**
     * Registers the recharge.orders.stuck gauge, which counts orders in a money state for more than 5 minutes whenever
     * metrics are scraped.
     */
    public StuckOrdersGauge(MeterRegistry registry, OrderStore orders, Clock clock) {
        Gauge.builder("recharge.orders.stuck",
                        () -> orders.countInStatusesOlderThan(WATCHED, clock.instant().minus(Duration.ofMinutes(5))))
                .description("Orders stuck in PAYMENT_PENDING/PAID/PROVISIONING/REFUND_INITIATED for more than 5 minutes")
                .register(registry);
    }
}
