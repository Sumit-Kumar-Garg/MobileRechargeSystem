package com.app.recharge.recommendation;

import com.app.recharge.catalog.PlanView;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * Explainable v1 recommender (pure logic, no I/O):
 * 1) usage fit: cheapest plan whose data covers average monthly usage plus 10% headroom;
 * 2) repeat purchase: the plan bought most often among the last five orders;
 * 3) popular default: the middle plan of the catalog.
 * A model-based recommender can replace this behind the same inputs; this stays as the fallback.
 */
public final class RecommendationRules {

    public enum Reason { USAGE_FIT, REPEAT_PURCHASE, POPULAR_DEFAULT }

    public record Result(PlanView plan, Reason reason, PlanView cheaperAlternative) { }

    static final double HEADROOM = 1.10;
    static final int HISTORY_WINDOW = 5;

    private RecommendationRules() { }

    /** @param recentPlanIds plan ids of completed orders, most recent first */
    public static Result recommend(List<PlanView> plans, List<UUID> recentPlanIds, OptionalDouble avgDataMb) {
        if (plans.isEmpty()) {
            throw new IllegalArgumentException("No plans to recommend from");
        }
        List<PlanView> sorted = plans.stream()
                .sorted(Comparator.comparingInt((PlanView p) -> p.price().amountMinor()).thenComparing(PlanView::code))
                .toList();

        PlanView chosen;
        Reason reason;
        if (avgDataMb.isPresent() && avgDataMb.getAsDouble() > 0) {
            double needed = avgDataMb.getAsDouble() * HEADROOM;
            chosen = sorted.stream()
                    .filter(p -> p.allowances().dataMb() != null && p.allowances().dataMb() >= needed)
                    .findFirst()
                    .orElseGet(() -> sorted.stream()
                            .max(Comparator.comparingInt(RecommendationRules::dataOf))
                            .orElse(sorted.get(0)));
            reason = Reason.USAGE_FIT;
        } else {
            Optional<PlanView> repeat = mostFrequent(sorted, recentPlanIds);
            if (repeat.isPresent()) {
                chosen = repeat.get();
                reason = Reason.REPEAT_PURCHASE;
            } else {
                chosen = sorted.get(sorted.size() / 2);
                reason = Reason.POPULAR_DEFAULT;
            }
        }
        PlanView cheapest = sorted.get(0);
        return new Result(chosen, reason, cheapest.id().equals(chosen.id()) ? null : cheapest);
    }

    /** The plan's data allowance in MB, 0 if not metered. */
    private static int dataOf(PlanView p) {
        return p.allowances().dataMb() == null ? 0 : p.allowances().dataMb();
    }

    /**
     * The plan bought most often among the last 5 purchases that are still on sale; ties go to the most recently
     * bought. Empty if none.
     */
    private static Optional<PlanView> mostFrequent(List<PlanView> plans, List<UUID> recentPlanIds) {
        Map<UUID, PlanView> byId = new HashMap<>();
        plans.forEach(p -> byId.put(p.id(), p));
        Map<UUID, Integer> counts = new HashMap<>();
        UUID best = null;
        int bestCount = 0;
        for (UUID id : recentPlanIds.stream().limit(HISTORY_WINDOW).toList()) {
            if (!byId.containsKey(id)) {
                continue;
            }
            int c = counts.merge(id, 1, Integer::sum);
            if (c > bestCount) {          // strict '>' keeps the most recent plan on ties
                best = id;
                bestCount = c;
            }
        }
        return Optional.ofNullable(best).map(byId::get);
    }
}
