package com.app.recharge.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.recharge.catalog.PlanService;
import com.app.recharge.catalog.PlanView;
import com.app.recharge.common.BusinessException;
import com.app.recharge.common.ErrorCode;
import com.app.recharge.common.RechargeMetrics;
import com.app.recharge.consent.ConsentPurpose;
import com.app.recharge.consent.ConsentService;
import com.app.recharge.number.NumberService;
import com.app.recharge.number.SubscriberNumber;
import com.app.recharge.order.OrderStore;
import com.app.recharge.order.OrderStatus;
import com.app.recharge.order.RechargeOrder;
import com.app.recharge.recommendation.agent.AdvisorService;
import com.app.recharge.support.MutableClock;
import com.app.recharge.support.TestData;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecommendationServiceTest {

    @Mock NumberService numbers;
    @Mock PlanService plans;
    @Mock OrderStore orders;
    @Mock UsageSummaryRepository usage;
    @Mock RecommendationLogRepository logRepository;
    @Mock ConsentService consent;
    @Mock AdvisorService advisor;

    final UUID user = UUID.randomUUID();
    final PlanView small = TestData.plan("S-28", 1000, 3000);
    final PlanView medium = TestData.plan("M-28", 1500, 10000);
    final PlanView large = TestData.plan("L-28", 2500, 30000);
    SubscriberNumber number;
    RecommendationService service;

    @BeforeEach
    void setUp() {
        service = new RecommendationService(numbers, plans, orders, usage, logRepository, consent, advisor,
                new RechargeMetrics(new SimpleMeterRegistry()), new MutableClock(Instant.parse("2026-11-07T10:00:00Z")));
        number = TestData.number(user);
        when(numbers.requireOwned(user, number.getId())).thenReturn(number);
        when(plans.getActivePlans("DE")).thenReturn(List.of(small, medium, large));
        when(advisor.currentAdvice(any(), any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void withoutConsentPersonalDataIsNeverRead() {
        when(consent.isGranted(user, ConsentPurpose.PERSONALIZATION)).thenReturn(false);

        RecommendationView view = service.recommend(user, number.getId()).orElseThrow();

        assertThat(view.reason()).isEqualTo("POPULAR_DEFAULT");
        assertThat(view.personalized()).isFalse();
        verify(orders, never()).recentByNumber(any(), any());
        verify(usage, never()).findTop3ByNumberIdOrderByMonthDesc(any());
        verify(logRepository, never()).save(any());
    }

    @Test
    void withConsentHistoryDrivesThePickAndIsLogged() {
        when(consent.isGranted(user, ConsentPurpose.PERSONALIZATION)).thenReturn(true);
        RechargeOrder past = TestData.order(user, large, Instant.now());
        when(orders.recentByNumber(number.getId(), OrderStatus.COMPLETED))
                .thenReturn(List.of(past));
        when(usage.findTop3ByNumberIdOrderByMonthDesc(number.getId())).thenReturn(List.of());

        RecommendationView view = service.recommend(user, number.getId()).orElseThrow();

        assertThat(view.planId()).isEqualTo(large.id());
        assertThat(view.reason()).isEqualTo("REPEAT_PURCHASE");
        assertThat(view.personalized()).isTrue();
        assertThat(view.cheaperAlternativePlanId()).isEqualTo(small.id());
        verify(logRepository).save(any(RecommendationLog.class));
    }

    @Test
    void withConsentUsageBeatsHistory() {
        when(consent.isGranted(user, ConsentPurpose.PERSONALIZATION)).thenReturn(true);
        when(orders.recentByNumber(any(), any())).thenReturn(List.of());
        when(usage.findTop3ByNumberIdOrderByMonthDesc(number.getId()))
                .thenReturn(List.of(new UsageSummary(number.getId(), java.time.LocalDate.of(2026, 10, 1), 9500)));

        RecommendationView view = service.recommend(user, number.getId()).orElseThrow();

        assertThat(view.planId()).isEqualTo(large.id());
        assertThat(view.reason()).isEqualTo("USAGE_FIT");
    }

    @Test
    void anyFailureDegradesToThePopularDefault() {
        when(consent.isGranted(user, ConsentPurpose.PERSONALIZATION)).thenReturn(true);
        when(orders.recentByNumber(any(), any()))
                .thenThrow(new IllegalStateException("db down"));

        RecommendationView view = service.recommend(user, number.getId()).orElseThrow();

        assertThat(view.planId()).isEqualTo(medium.id());
        assertThat(view.personalized()).isFalse();
    }

    @Test
    void storedAdviceFromTheAgentIsServedWithItsExplanation() {
        when(consent.isGranted(user, ConsentPurpose.PERSONALIZATION)).thenReturn(true);
        when(advisor.currentAdvice(number.getId(), List.of(small, medium, large), "de")).thenReturn(Optional.of(
                new AdvisorService.CurrentAdvice(large, "Du nutzt etwa 9 GB pro Monat.", "test-model")));

        RecommendationView view = service.recommend(user, number.getId(), "de").orElseThrow();

        assertThat(view.planId()).isEqualTo(large.id());
        assertThat(view.reason()).isEqualTo("AI_ADVISOR");
        assertThat(view.explanation()).isEqualTo("Du nutzt etwa 9 GB pro Monat.");
        assertThat(view.modelVersion()).isEqualTo("agent:test-model");
        assertThat(view.personalized()).isTrue();
        assertThat(view.cheaperAlternativePlanId()).isEqualTo(small.id());
        verify(advisor, never()).refreshInBackground(any(), any(), any(), any());
        verify(logRepository).save(any(RecommendationLog.class));
    }

    @Test
    void withoutFreshAdviceTheRulesAnswerNowAndTheAgentIsAskedInTheBackground() {
        when(consent.isGranted(user, ConsentPurpose.PERSONALIZATION)).thenReturn(true);
        when(orders.recentByNumber(any(), any())).thenReturn(List.of());
        when(usage.findTop3ByNumberIdOrderByMonthDesc(number.getId()))
                .thenReturn(List.of(new UsageSummary(number.getId(), java.time.LocalDate.of(2026, 10, 1), 9500)));

        RecommendationView view = service.recommend(user, number.getId(), "en").orElseThrow();

        assertThat(view.reason()).isEqualTo("USAGE_FIT");
        assertThat(view.explanation()).isNull();
        verify(advisor).refreshInBackground(user, number.getId(), List.of(small, medium, large), "en");
    }

    @Test
    void withoutConsentTheAgentIsNeverAskedAndStoredAdviceIsRemoved() {
        when(consent.isGranted(user, ConsentPurpose.PERSONALIZATION)).thenReturn(false);

        service.recommend(user, number.getId(), "en");

        verify(advisor).forget(number.getId());
        verify(advisor, never()).currentAdvice(any(), any(), any());
        verify(advisor, never()).refreshInBackground(any(), any(), any(), any());
    }

    @Test
    void anUnsupportedLanguageFallsBackToEnglish() {
        when(consent.isGranted(user, ConsentPurpose.PERSONALIZATION)).thenReturn(true);
        when(orders.recentByNumber(any(), any())).thenReturn(List.of());
        when(usage.findTop3ByNumberIdOrderByMonthDesc(any())).thenReturn(List.of());

        service.recommend(user, number.getId(), "fr");

        verify(advisor).currentAdvice(number.getId(), List.of(small, medium, large), "en");
    }

    @Test
    void aFailingAdvisorNeverBreaksTheRecommendation() {
        when(consent.isGranted(user, ConsentPurpose.PERSONALIZATION)).thenReturn(true);
        when(advisor.currentAdvice(any(), any(), any())).thenThrow(new IllegalStateException("advice store down"));

        RecommendationView view = service.recommend(user, number.getId(), "en").orElseThrow();

        assertThat(view.planId()).isEqualTo(medium.id());
        assertThat(view.personalized()).isFalse();
    }

    @Test
    void emptyCatalogMeansNoRecommendation() {
        when(plans.getActivePlans("DE")).thenReturn(List.of());
        assertThat(service.recommend(user, number.getId())).isEmpty();
    }

    @Test
    void foreignNumberIsRejected() {
        UUID other = UUID.randomUUID();
        when(numbers.requireOwned(user, other))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Number not found"));
        assertThatThrownBy(() -> service.recommend(user, other)).isInstanceOf(BusinessException.class);
    }
}
