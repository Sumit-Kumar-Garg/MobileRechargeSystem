package com.app.recharge.recommendation.agent;

import com.app.recharge.catalog.PlanView;
import com.app.recharge.common.JobLease;
import com.app.recharge.common.RechargeMetrics;
import com.app.recharge.ratelimit.RateLimiter;
import com.app.recharge.recommendation.spi.LlmUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Keeps the agent off the request path.
 * - {@link #currentAdvice} is a single indexed read: the app gets an answer in milliseconds.
 * - {@link #refreshInBackground} starts an agent run on another thread when there is no fresh advice. The next
 *   time the customer opens the plan list, the advice is there.
 * Cost and abuse control: one run per number at a time (lease), a daily run limit per user, a step limit and
 * a token limit per run, and stored advice is reused for {@code adviceTtl}.
 */
@Service
public class AdvisorService {

    private static final Logger log = LoggerFactory.getLogger(AdvisorService.class);
    static final Duration RUN_LEASE = Duration.ofMinutes(3);

    /** What the recommendation endpoint needs from a stored advice. */
    public record CurrentAdvice(PlanView plan, String explanation, String model) { }

    private final RechargeAdvisorAgent agent;
    private final LlmGateway llm;
    private final StoredAdviceRepository store;
    private final JobLease lease;
    private final RateLimiter limiter;
    private final Executor executor;
    private final AdvisorProperties props;
    private final RechargeMetrics metrics;
    private final Clock clock;

    public AdvisorService(RechargeAdvisorAgent agent, LlmGateway llm, StoredAdviceRepository store, JobLease lease,
                          RateLimiter limiter, @Qualifier("advisorExecutor") Executor executor,
                          AdvisorProperties props, RechargeMetrics metrics, Clock clock) {
        this.agent = agent;
        this.llm = llm;
        this.store = store;
        this.lease = lease;
        this.limiter = limiter;
        this.executor = executor;
        this.props = props;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** True when a language model is configured; otherwise only the rules are used. */
    public boolean isEnabled() {
        return llm.isConfigured();
    }

    /**
     * The stored advice, if it is still valid, in the requested language, and its plan is still on sale.
     * A plan that left the catalog since the agent ran is never recommended.
     */
    public Optional<CurrentAdvice> currentAdvice(UUID numberId, List<PlanView> catalog, String language) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        return store.findById(numberId)
                .filter(a -> a.getValidUntil().isAfter(clock.instant()))
                .filter(a -> a.getLanguage().equals(language))
                .flatMap(a -> catalog.stream()
                        .filter(p -> p.id().equals(a.getPlanId()))
                        .findFirst()
                        .map(p -> new CurrentAdvice(p, a.getExplanation(), a.getModel())));
    }

    /** Starts an agent run unless one is running for this number or the user used up today's runs. Never throws. */
    public void refreshInBackground(UUID userId, UUID numberId, List<PlanView> catalog, String language) {
        if (!isEnabled()) {
            return;
        }
        try {
            if (!lease.tryAcquire(leaseName(numberId), RUN_LEASE)) {
                return;                                   // a run for this number is already in flight
            }
            if (!limiter.hit("advisor:" + userId, props.runsPerUserPerDay(), Duration.ofDays(1)).allowed()) {
                metrics.advisor("rate_limited");
                lease.release(leaseName(numberId));
                return;
            }
            executor.execute(() -> run(userId, numberId, catalog, language));
        } catch (RejectedExecutionException e) {
            lease.release(leaseName(numberId));
        } catch (RuntimeException e) {
            log.warn("Could not start an advisor run", e);
        }
    }

    /** One agent run. Every outcome is counted; no outcome can reach the customer as an error. */
    void run(UUID userId, UUID numberId, List<PlanView> catalog, String language) {
        try {
            Optional<Advice> advice = agent.advise(numberId, catalog, language);
            if (advice.isPresent()) {
                store.save(StoredAdvice.of(userId, numberId, advice.get(), language, clock.instant(), props.adviceTtl()));
                metrics.advisor("advised");
                metrics.advisorTokens(advice.get().inputTokens(), advice.get().outputTokens());
            } else {
                metrics.advisor("no_valid_answer");
            }
        } catch (LlmUnavailableException e) {
            metrics.advisor("model_unavailable");
            log.warn("Advisor run skipped, model unavailable: {}", e.getMessage());
        } catch (RuntimeException e) {
            metrics.advisor("error");
            log.error("Advisor run failed", e);
        } finally {
            lease.release(leaseName(numberId));
        }
    }

    /** Removes stored advice, for example when the customer withdraws personalization consent. */
    public void forget(UUID numberId) {
        if (store.existsById(numberId)) {
            store.deleteById(numberId);
        }
    }

    /** Name of the lease that allows one advisor run per number at a time. */
    private static String leaseName(UUID numberId) {
        return "advisor:" + numberId;                     // 44 characters, the lease name column holds 60
    }
}
