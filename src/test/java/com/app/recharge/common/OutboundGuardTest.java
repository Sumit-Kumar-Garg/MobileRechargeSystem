package com.app.recharge.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OutboundGuardTest {

    static class Unavailable extends RuntimeException {
        Unavailable(String message) {
            super(message);
        }
    }

    private static OutboundGuard guard(int maxConcurrent, Duration timeout) {
        CircuitBreaker breaker = CircuitBreakers.create("guard-" + System.nanoTime(), Unavailable.class, 4, 4);
        return new OutboundGuard("test", breaker, maxConcurrent, timeout, Unavailable::new);
    }

    @Test
    void passesResultsAndBusinessExceptionsThrough() {
        OutboundGuard guard = guard(2, Duration.ofSeconds(1));

        assertThat(guard.call(() -> "ok")).isEqualTo("ok");
        assertThatThrownBy(() -> guard.call(() -> {
            throw new IllegalArgumentException("business");
        })).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCallThatTakesTooLongIsReportedAsUnavailable() {
        OutboundGuard guard = guard(2, Duration.ofMillis(100));

        assertThatThrownBy(() -> guard.call(() -> {
            sleep(2_000);
            return "late";
        })).isInstanceOf(Unavailable.class).hasMessageContaining("timed out");
    }

    @Test
    void theBulkheadRejectsCallsBeyondTheLimitInsteadOfQueueingThem() throws Exception {
        OutboundGuard guard = guard(1, Duration.ofSeconds(5));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread slow = Thread.ofVirtual().start(() -> guard.call(() -> {
            entered.countDown();
            await(release);
            return "slow";
        }));
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> guard.call(() -> "second"))
                .isInstanceOf(Unavailable.class).hasMessageContaining("bulkhead");

        release.countDown();
        slow.join(2_000);
        assertThat(guard.call(() -> "after")).isEqualTo("after");       // the permit was returned
    }

    @Test
    void repeatedFailuresOpenTheCircuitAndTheProviderIsNoLongerCalled() {
        OutboundGuard guard = guard(5, Duration.ofSeconds(1));
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> guard.call(() -> {
                calls.incrementAndGet();
                throw new Unavailable("down");
            })).isInstanceOf(Unavailable.class);
        }

        assertThatThrownBy(() -> guard.call(() -> {
            calls.incrementAndGet();
            return "x";
        })).isInstanceOf(Unavailable.class).hasMessageContaining("circuit");
        assertThat(calls.get()).isEqualTo(4);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void theCallersLogContextIsVisibleInsideTheProviderCallAndDoesNotLeakOut() {
        OutboundGuard guard = guard(2, Duration.ofSeconds(1));
        org.slf4j.MDC.put("correlationId", "cid-123");
        try {
            String seenByProviderCall = guard.call(() -> org.slf4j.MDC.get("correlationId"));

            assertThat(seenByProviderCall).isEqualTo("cid-123");
            assertThat(org.slf4j.MDC.get("correlationId")).isEqualTo("cid-123");     // the caller keeps its own
        } finally {
            org.slf4j.MDC.clear();
        }
    }

    @Test
    void theCurrentObservationIsCarriedOntoTheProviderCallSoTheTraceDoesNotBreak() {
        OutboundGuard guard = guard(2, Duration.ofSeconds(1));
        var registry = io.micrometer.observation.ObservationRegistry.create();
        registry.observationConfig().observationHandler(context -> true);          // makes observations real, not no-op
        var observation = io.micrometer.observation.Observation.start("pay", registry);

        try (var scope = observation.openScope()) {
            var seen = guard.call(registry::getCurrentObservation);

            assertThat(seen).isSameAs(observation);
        } finally {
            observation.stop();
        }
    }
}
