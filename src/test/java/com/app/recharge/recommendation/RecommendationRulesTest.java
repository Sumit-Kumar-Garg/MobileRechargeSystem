package com.app.recharge.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.recharge.catalog.PlanView;
import com.app.recharge.support.TestData;
import java.util.List;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class RecommendationRulesTest {

    private final PlanView small = TestData.plan("S-28", 1000, 3000);
    private final PlanView medium = TestData.plan("M-28", 1500, 10000);
    private final PlanView large = TestData.plan("L-28", 2500, 30000);
    // deliberately unsorted: the rules must not depend on input order
    private final List<PlanView> catalog = List.of(large, small, medium);

    @Test
    void usageFitPicksTheCheapestPlanThatCoversUsageWithHeadroom() {
        var r = RecommendationRules.recommend(catalog, List.of(), OptionalDouble.of(8000));    // needs 8800
        assertThat(r.plan()).isEqualTo(medium);
        assertThat(r.reason()).isEqualTo(RecommendationRules.Reason.USAGE_FIT);
    }

    @Test
    void headroomPushesBorderlineUsageToTheNextPlan() {
        var r = RecommendationRules.recommend(catalog, List.of(), OptionalDouble.of(9500));    // needs 10450 > 10000
        assertThat(r.plan()).isEqualTo(large);
    }

    @Test
    void usageBeyondEveryPlanGetsTheBiggestPlan() {
        var r = RecommendationRules.recommend(catalog, List.of(), OptionalDouble.of(50000));
        assertThat(r.plan()).isEqualTo(large);
        assertThat(r.reason()).isEqualTo(RecommendationRules.Reason.USAGE_FIT);
    }

    @Test
    void lightUsageGetsTheCheapestPlanAndNoCheaperAlternative() {
        var r = RecommendationRules.recommend(catalog, List.of(), OptionalDouble.of(1000));
        assertThat(r.plan()).isEqualTo(small);
        assertThat(r.cheaperAlternative()).isNull();
    }

    @Test
    void alwaysOffersTheCheapestPlanAsAnAlternative() {
        var r = RecommendationRules.recommend(catalog, List.of(), OptionalDouble.of(9500));
        assertThat(r.cheaperAlternative()).isEqualTo(small);
    }

    @Test
    void repeatPurchasePicksTheMostFrequentRecentPlan() {
        var r = RecommendationRules.recommend(catalog, List.of(large.id(), medium.id(), large.id()), OptionalDouble.empty());
        assertThat(r.plan()).isEqualTo(large);
        assertThat(r.reason()).isEqualTo(RecommendationRules.Reason.REPEAT_PURCHASE);
    }

    @Test
    void tiesGoToTheMostRecentPurchase() {
        var r = RecommendationRules.recommend(catalog, List.of(medium.id(), large.id()), OptionalDouble.empty());
        assertThat(r.plan()).isEqualTo(medium);
    }

    @Test
    void onlyTheLastFivePurchasesCount() {
        List<java.util.UUID> history = List.of(medium.id(), medium.id(), small.id(), small.id(), small.id(),
                large.id(), large.id(), large.id(), large.id());
        var r = RecommendationRules.recommend(catalog, history, OptionalDouble.empty());
        assertThat(r.plan()).isEqualTo(small);
    }

    @Test
    void plansNoLongerOnSaleAreIgnoredInHistory() {
        var r = RecommendationRules.recommend(catalog, List.of(java.util.UUID.randomUUID()), OptionalDouble.empty());
        assertThat(r.reason()).isEqualTo(RecommendationRules.Reason.POPULAR_DEFAULT);
    }

    @Test
    void withoutAnySignalTheMiddlePlanIsTheDefault() {
        var r = RecommendationRules.recommend(catalog, List.of(), OptionalDouble.empty());
        assertThat(r.plan()).isEqualTo(medium);
        assertThat(r.reason()).isEqualTo(RecommendationRules.Reason.POPULAR_DEFAULT);
    }

    @Test
    void zeroUsageIsTreatedAsNoSignal() {
        var r = RecommendationRules.recommend(catalog, List.of(large.id()), OptionalDouble.of(0));
        assertThat(r.reason()).isEqualTo(RecommendationRules.Reason.REPEAT_PURCHASE);
    }

    @Test
    void singlePlanCatalogWorks() {
        var r = RecommendationRules.recommend(List.of(small), List.of(), OptionalDouble.empty());
        assertThat(r.plan()).isEqualTo(small);
        assertThat(r.cheaperAlternative()).isNull();
    }

    @Test
    void emptyCatalogIsAProgrammingError() {
        assertThatThrownBy(() -> RecommendationRules.recommend(List.of(), List.of(), OptionalDouble.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
