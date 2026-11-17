package com.app.recharge.catalog.spi;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.recharge.catalog.CatalogSyncService;
import java.util.List;
import org.junit.jupiter.api.Test;

/** CONTRACT TEST KIT for plan providers: the feed must be something the catalog will accept in full. */
public abstract class PlanProviderContract {

    protected abstract PlanProvider provider();

    @Test
    void hasAStableLowerCaseId() {
        assertThat(provider().id()).matches("^[a-z0-9-]{2,40}$");
    }

    @Test
    void returnsACompleteNonEmptyFeedOfValidPlans() {
        List<ProviderPlan> plans = provider().fetchPlans();
        assertThat(plans).isNotEmpty();
        assertThat(plans).allMatch(CatalogSyncService::isValid, "accepted by the catalog");
    }

    @Test
    void planCodesAreUniquePerMarket() {
        List<String> keys = provider().fetchPlans().stream().map(p -> p.market() + "|" + p.code()).toList();
        assertThat(keys).doesNotHaveDuplicates();
    }

    @Test
    void theFeedIsStableBetweenCalls() {
        assertThat(provider().fetchPlans()).isEqualTo(provider().fetchPlans());
    }
}
