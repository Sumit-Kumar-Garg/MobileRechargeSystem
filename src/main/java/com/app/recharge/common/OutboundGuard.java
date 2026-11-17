package com.app.recharge.common;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.MDC;

/**
 * Wraps every call to an external provider with three protections, all from Resilience4j:
 * a bulkhead (at most N concurrent calls, never queued, so a slow provider cannot use up request threads),
 * a time limiter (a hard timeout; the port contract alone cannot guarantee one), and a circuit breaker.
 * Every protective failure is reported as the provider's "unavailable" exception, which callers treat as
 * "outcome unknown", never as a business failure.
 *
 * The call runs on a virtual thread so it can be abandoned on timeout. The caller's trace and log context
 * (current span, correlation id) is carried onto that thread, so provider calls appear inside the request's
 * trace and their log lines keep the correlation id.
 */
public final class OutboundGuard {

    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final ContextSnapshotFactory SNAPSHOTS = ContextSnapshotFactory.builder().build();

    private final String name;
    private final CircuitBreaker breaker;
    private final Bulkhead bulkhead;
    private final TimeLimiter timeLimiter;
    private final Function<String, ? extends RuntimeException> unavailable;

    /**
     * Creates a guard for one provider: {@code maxConcurrent} parallel calls, a hard {@code timeout}, the given circuit
     * breaker, and {@code unavailable} to build the provider's "unavailable" exception from a message.
     */
    public OutboundGuard(String name, CircuitBreaker breaker, int maxConcurrent, Duration timeout,
                         Function<String, ? extends RuntimeException> unavailable) {
        this.name = name;
        this.breaker = breaker;
        this.bulkhead = Bulkhead.of(name, BulkheadConfig.custom()
                .maxConcurrentCalls(maxConcurrent)
                .maxWaitDuration(Duration.ZERO)          // reject at once; waiting would hold a request thread
                .build());
        this.timeLimiter = TimeLimiter.of(name, TimeLimiterConfig.custom()
                .timeoutDuration(timeout)
                .cancelRunningFuture(true)
                .build());
        this.unavailable = unavailable;
    }

    /**
     * Runs the provider call through bulkhead, circuit breaker and timeout. Returns its result or rethrows its
     * exception; a full bulkhead, open circuit, timeout or interrupt becomes the provider's "unavailable" exception.
     */
    public <T> T call(Supplier<T> supplier) {
        try {
            return bulkhead.executeSupplier(() -> breaker.executeSupplier(() -> runWithTimeout(supplier)));
        } catch (BulkheadFullException e) {
            throw unavailable.apply(name + " bulkhead is full");
        } catch (CallNotPermittedException e) {
            throw unavailable.apply(name + " circuit is open");
        }
    }

    /**
     * Runs the call on a virtual thread with the caller's context and gives up after the timeout, cancelling the call.
     */
    private <T> T runWithTimeout(Supplier<T> supplier) {
        Callable<T> task = withCallerContext(supplier);
        try {
            return timeLimiter.executeFutureSupplier(() -> EXECUTOR.submit(task));
        } catch (TimeoutException e) {
            throw unavailable.apply(name + " call timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable.apply(name + " call interrupted");
        } catch (RuntimeException e) {
            throw e;                                     // the provider's own answer or its "unavailable"
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Captures the caller's observation (trace) context and MDC now, restores both inside the worker thread. */
    private static <T> Callable<T> withCallerContext(Supplier<T> supplier) {
        ContextSnapshot snapshot = SNAPSHOTS.captureAll();
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        return () -> {
            if (mdc != null) {
                MDC.setContextMap(mdc);
            }
            try (ContextSnapshot.Scope scope = snapshot.setThreadLocals()) {
                return supplier.get();
            } finally {
                MDC.clear();
            }
        };
    }
}
