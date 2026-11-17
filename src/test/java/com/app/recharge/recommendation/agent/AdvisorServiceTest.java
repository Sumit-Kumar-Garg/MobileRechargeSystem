package com.app.recharge.recommendation.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.recharge.catalog.PlanView;
import com.app.recharge.common.JobLease;
import com.app.recharge.common.RechargeMetrics;
import com.app.recharge.ratelimit.RateLimitResult;
import com.app.recharge.ratelimit.RateLimiter;
import com.app.recharge.recommendation.spi.LlmUnavailableException;
import com.app.recharge.support.MutableClock;
import com.app.recharge.support.TestData;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdvisorServiceTest {

    @Mock RechargeAdvisorAgent agent;
    @Mock LlmGateway llm;
    @Mock StoredAdviceRepository store;
    @Mock JobLease lease;
    @Mock RateLimiter limiter;

    final MutableClock clock = new MutableClock(Instant.parse("2026-11-07T10:00:00Z"));
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final UUID user = UUID.randomUUID();
    final UUID numberId = UUID.randomUUID();
    final PlanView small = TestData.plan("S-28", 1000, 3000);
    final PlanView large = TestData.plan("L-28", 2500, 30000);
    final List<PlanView> catalog = List.of(small, large);
    final AtomicReference<StoredAdvice> stored = new AtomicReference<>();
    final AdvisorProperties props = new AdvisorProperties("test", 6, Duration.ofSeconds(2), 5, 400,
            Duration.ofHours(24), 5, "", "", "");
    AdvisorService service;

    @BeforeEach
    void setUp() {
        // the executor runs the task at once, on the calling thread, so tests see the result immediately
        service = new AdvisorService(agent, llm, store, lease, limiter, Runnable::run, props,
                new RechargeMetrics(meters), clock);
        when(llm.isConfigured()).thenReturn(true);
        when(lease.tryAcquire(anyString(), any())).thenReturn(true);
        when(limiter.hit(anyString(), anyInt(), any())).thenReturn(new RateLimitResult(true, 4, 0));
        when(store.save(any(StoredAdvice.class))).thenAnswer(i -> {
            stored.set(i.getArgument(0));
            return i.getArgument(0);
        });
        when(store.findById(numberId)).thenAnswer(i -> Optional.ofNullable(stored.get()));
    }

    private Advice advice(PlanView plan) {
        return new Advice(plan, "You use about 9 GB a month.", "test-model", 2, 300, 40);
    }

    private double runs(String outcome) {
        var counter = meters.find("recharge.advisor.runs").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void aSuccessfulRunIsStoredAndThenServedWithoutCallingTheModelAgain() {
        when(agent.advise(numberId, catalog, "en")).thenReturn(Optional.of(advice(large)));

        service.refreshInBackground(user, numberId, catalog, "en");
        Optional<AdvisorService.CurrentAdvice> current = service.currentAdvice(numberId, catalog, "en");

        assertThat(current).isPresent();
        assertThat(current.get().plan()).isEqualTo(large);
        assertThat(current.get().explanation()).isEqualTo("You use about 9 GB a month.");
        assertThat(runs("advised")).isEqualTo(1);
        assertThat(meters.find("recharge.advisor.tokens").tag("direction", "input").counter().count()).isEqualTo(300);
        verify(lease).release("advisor:" + numberId);
    }

    @Test
    void adviceExpires() {
        when(agent.advise(numberId, catalog, "en")).thenReturn(Optional.of(advice(large)));
        service.refreshInBackground(user, numberId, catalog, "en");

        clock.advance(Duration.ofHours(25));

        assertThat(service.currentAdvice(numberId, catalog, "en")).isEmpty();
    }

    @Test
    void adviceForAPlanThatLeftTheCatalogIsNotServed() {
        when(agent.advise(numberId, catalog, "en")).thenReturn(Optional.of(advice(large)));
        service.refreshInBackground(user, numberId, catalog, "en");

        assertThat(service.currentAdvice(numberId, List.of(small), "en")).isEmpty();
    }

    @Test
    void adviceInAnotherLanguageIsNotServed() {
        when(agent.advise(numberId, catalog, "en")).thenReturn(Optional.of(advice(large)));
        service.refreshInBackground(user, numberId, catalog, "en");

        assertThat(service.currentAdvice(numberId, catalog, "de")).isEmpty();
    }

    @Test
    void onlyOneRunPerNumberAtATime() {
        when(lease.tryAcquire(anyString(), any())).thenReturn(false);

        service.refreshInBackground(user, numberId, catalog, "en");

        verify(agent, never()).advise(any(), any(), any());
    }

    @Test
    void theDailyRunLimitPerUserStopsFurtherModelCalls() {
        when(limiter.hit(anyString(), anyInt(), any())).thenReturn(new RateLimitResult(false, 0, 3600));

        service.refreshInBackground(user, numberId, catalog, "en");

        verify(agent, never()).advise(any(), any(), any());
        verify(limiter).hit("advisor:" + user, 5, Duration.ofDays(1));
        verify(lease).release("advisor:" + numberId);
        assertThat(runs("rate_limited")).isEqualTo(1);
    }

    @Test
    void aModelOutageIsCountedStoresNothingAndFreesTheLease() {
        when(agent.advise(any(), any(), any())).thenThrow(new LlmUnavailableException("down"));

        service.refreshInBackground(user, numberId, catalog, "en");

        verify(store, never()).save(any(StoredAdvice.class));
        verify(lease).release("advisor:" + numberId);
        assertThat(runs("model_unavailable")).isEqualTo(1);
    }

    @Test
    void anInvalidAnswerIsCountedAndStoresNothing() {
        when(agent.advise(any(), any(), any())).thenReturn(Optional.empty());

        service.refreshInBackground(user, numberId, catalog, "en");

        verify(store, never()).save(any(StoredAdvice.class));
        assertThat(runs("no_valid_answer")).isEqualTo(1);
    }

    @Test
    void withoutAConfiguredModelNothingHappens() {
        when(llm.isConfigured()).thenReturn(false);

        service.refreshInBackground(user, numberId, catalog, "en");

        assertThat(service.currentAdvice(numberId, catalog, "en")).isEmpty();
        verify(lease, never()).tryAcquire(anyString(), any());
        verify(store, never()).findById(any());
    }

    @Test
    void forgetRemovesStoredAdvice() {
        when(store.existsById(numberId)).thenReturn(true);

        service.forget(numberId);

        verify(store).deleteById(numberId);
    }
}
