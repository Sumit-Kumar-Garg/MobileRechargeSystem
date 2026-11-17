package com.app.recharge.providers.travelsim;

import com.app.recharge.catalog.spi.PlanProvider;
import com.app.recharge.catalog.spi.ProviderPlan;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Example of a second plan provider owned by its own team: roaming packs. A real implementation would call
 * the partner's API here. Depends only on the catalog {@code spi} package.
 */
@Component
@ConditionalOnProperty(name = "app.providers.travelsim.enabled", havingValue = "true")
public class TravelSimPlanProvider implements PlanProvider {

    public static final String ID = "travelsim";

    @Override
    public String id() {
        return ID;
    }

    /** The two fixed demo travel packs for DE (7 and 30 days). A real adapter would call the partner's API. */
    @Override
    public List<ProviderPlan> fetchPlans() {
        return List.of(
                new ProviderPlan("TRAVEL-7", "DE", "Travel pack 7 days", 900, "EUR", 7, 2000, null, null,
                        List.of("Works in 40 countries")),
                new ProviderPlan("TRAVEL-30", "DE", "Travel pack 30 days", 2900, "EUR", 30, 10000, 100, null,
                        List.of("Works in 40 countries", "Calls back home included")));
    }
}
