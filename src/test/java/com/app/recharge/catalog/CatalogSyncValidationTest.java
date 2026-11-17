package com.app.recharge.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.recharge.catalog.spi.ProviderPlan;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogSyncValidationTest {

    private static ProviderPlan plan(String code, String market, String name, int price, String currency, int days, Integer data) {
        return new ProviderPlan(code, market, name, price, currency, days, data, null, null, List.of());
    }

    @Test
    void acceptsAWellFormedPlan() {
        assertThat(CatalogSyncService.isValid(plan("TRAVEL-7", "DE", "Travel pack", 900, "EUR", 7, 2000))).isTrue();
        assertThat(CatalogSyncService.isValid(plan("X", "PL", "Unmetered", 100, "PLN", 1, null))).isTrue();
    }

    @Test
    void rejectsWhatCouldHurtCustomersOrTheCatalog() {
        assertThat(CatalogSyncService.isValid(null)).isFalse();
        assertThat(CatalogSyncService.isValid(plan("T", "DE", "Free!", 0, "EUR", 7, 1))).isFalse();          // price must be > 0
        assertThat(CatalogSyncService.isValid(plan("T", "DE", "Negative", -5, "EUR", 7, 1))).isFalse();
        assertThat(CatalogSyncService.isValid(plan("T", "de", "Bad market", 100, "EUR", 7, 1))).isFalse();
        assertThat(CatalogSyncService.isValid(plan("T", "DE", "Bad currency", 100, "eur", 7, 1))).isFalse();
        assertThat(CatalogSyncService.isValid(plan("T", "DE", "No validity", 100, "EUR", 0, 1))).isFalse();
        assertThat(CatalogSyncService.isValid(plan("T", "DE", "Negative data", 100, "EUR", 7, -1))).isFalse();
        assertThat(CatalogSyncService.isValid(plan("bad code!", "DE", "Bad code", 100, "EUR", 7, 1))).isFalse();
        assertThat(CatalogSyncService.isValid(plan("T", "DE", " ", 100, "EUR", 7, 1))).isFalse();
        assertThat(CatalogSyncService.isValid(plan("T", "DE", "x".repeat(121), 100, "EUR", 7, 1))).isFalse();
    }
}
