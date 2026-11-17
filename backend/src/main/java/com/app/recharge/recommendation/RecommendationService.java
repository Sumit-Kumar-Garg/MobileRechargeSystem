package com.app.recharge.recommendation;

import com.app.recharge.catalog.PlanService;
import com.app.recharge.catalog.PlanView;
import com.app.recharge.common.RechargeMetrics;
import com.app.recharge.consent.ConsentPurpose;
import com.app.recharge.consent.ConsentService;
import com.app.recharge.number.NumberService;
import com.app.recharge.number.SubscriberNumber;
import com.app.recharge.order.OrderStore;
import com.app.recharge.order.OrderStatus;
import com.app.recharge.order.RechargeOrder;
import com.app.recharge.recommendation.agent.AdvisorService;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Suggests a plan. Personal data (order history, usage) is used only with personalization consent;
 * without it the user still gets the non-personal popular default. Any failure degrades to that default,
 * so the recommendation can never block a recharge.
 *
 * With consent there are two recommenders:
 * - the advisor agent (a language model with read-only tools). It runs in the background; this class only reads
 *   its stored advice, so the request stays fast.
 * - the rules. They answer immediately whenever there is no fresh advice, and they are what remains when the
 *   model is off, slow, wrong or too expensive.
 */
@Service
public class RecommendationService {

    static final String MODEL_VERSION = "rules-v1";
    static final String AI_REASON = "AI_ADVISOR";
    static final Set<String> ADVISOR_LANGUAGES = Set.of("en", "de");
    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);

    private final NumberService numbers;
    private final PlanService plans;
    private final OrderStore orders;
    private final UsageSummaryRepository usage;
    private final RecommendationLogRepository logRepository;
    private final ConsentService consent;
    private final AdvisorService advisor;
    private final RechargeMetrics metrics;
    private final Clock clock;

    public RecommendationService(NumberService numbers, PlanService plans, OrderStore orders,
                                 UsageSummaryRepository usage, RecommendationLogRepository logRepository,
                                 ConsentService consent, AdvisorService advisor, RechargeMetrics metrics,
                                 Clock clock) {
        this.numbers = numbers;
        this.plans = plans;
        this.orders = orders;
        this.usage = usage;
        this.logRepository = logRepository;
        this.consent = consent;
        this.advisor = advisor;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Recommends a plan with English advisor text. See {@link #recommend(UUID, UUID, String)}. */
    public Optional<RecommendationView> recommend(UUID userId, UUID numberId) {
        return recommend(userId, numberId, "en");
    }

    /**
     * Recommends a plan for one of the user's numbers. With personalization consent, returns fresh stored AI advice if
     * there is some (otherwise starts a background advisor run and uses the rules on history and usage), and logs the
     * recommendation. Without consent, deletes stored advice and returns the popular default. Any error also falls back
     * to the default. Empty if the market has no plans.
     */
    public Optional<RecommendationView> recommend(UUID userId, UUID numberId, String requestedLanguage) {
        String language = ADVISOR_LANGUAGES.contains(requestedLanguage) ? requestedLanguage : "en";
        SubscriberNumber number = numbers.requireOwned(userId, numberId);
        List<PlanView> catalog = plans.getActivePlans(number.getMarket());
        if (catalog.isEmpty()) {
            return Optional.empty();
        }
        boolean personalized = consent.isGranted(userId, ConsentPurpose.PERSONALIZATION);
        try {
            if (!personalized) {
                advisor.forget(numberId);            // consent withdrawn: stored advice goes too
            } else {
                Optional<AdvisorService.CurrentAdvice> advice = advisor.currentAdvice(numberId, catalog, language);
                if (advice.isPresent()) {
                    return Optional.of(fromAdvisor(userId, numberId, advice.get(), catalog));
                }
                advisor.refreshInBackground(userId, numberId, catalog, language);   // ready for the next visit
            }
            List<UUID> history = personalized
                    ? orders.recentByNumber(numberId, OrderStatus.COMPLETED)
                            .stream().map(RechargeOrder::getPlanId).toList()
                    : List.of();
            OptionalDouble avgData = personalized ? averageData(numberId) : OptionalDouble.empty();
            RecommendationRules.Result r = RecommendationRules.recommend(catalog, history, avgData);
            if (personalized) {
                logRepository.save(new RecommendationLog(userId, numberId, r.plan().id(), r.reason().name(),
                        MODEL_VERSION, clock.instant()));
            }
            metrics.recommendation(r.reason().name());
            return Optional.of(toView(r, personalized && r.reason() != RecommendationRules.Reason.POPULAR_DEFAULT));
        } catch (RuntimeException e) {
            log.warn("Recommendation failed, falling back to the popular default", e);
            metrics.recommendation("FALLBACK");
            return Optional.of(toView(RecommendationRules.recommend(catalog, List.of(), OptionalDouble.empty()), false));
        }
    }

    /**
     * Turns stored AI advice into the API view, logs it, and adds the cheapest plan as an alternative if it is a
     * different plan.
     */
    private RecommendationView fromAdvisor(UUID userId, UUID numberId, AdvisorService.CurrentAdvice advice,
                                           List<PlanView> catalog) {
        String modelVersion = "agent:" + advice.model();
        logRepository.save(new RecommendationLog(userId, numberId, advice.plan().id(), AI_REASON,
                modelVersion.length() <= 30 ? modelVersion : modelVersion.substring(0, 30), clock.instant()));
        metrics.recommendation(AI_REASON);
        PlanView cheapest = catalog.stream()
                .min(Comparator.comparingInt((PlanView p) -> p.price().amountMinor()).thenComparing(PlanView::code))
                .orElse(advice.plan());
        return new RecommendationView(advice.plan().id(), advice.plan().code(), AI_REASON, true,
                cheapest.id().equals(advice.plan().id()) ? null : cheapest.id(), modelVersion, advice.explanation());
    }

    /** Average monthly data use in MB over the number's last 3 recorded months; empty if none. */
    private OptionalDouble averageData(UUID numberId) {
        return usage.findTop3ByNumberIdOrderByMonthDesc(numberId).stream()
                .mapToInt(UsageSummary::getDataMbUsed).average();
    }

    /** Turns a rules result into the API view. */
    private static RecommendationView toView(RecommendationRules.Result r, boolean personalized) {
        return new RecommendationView(r.plan().id(), r.plan().code(), r.reason().name(), personalized,
                r.cheaperAlternative() == null ? null : r.cheaperAlternative().id(), MODEL_VERSION, null);
    }
}
