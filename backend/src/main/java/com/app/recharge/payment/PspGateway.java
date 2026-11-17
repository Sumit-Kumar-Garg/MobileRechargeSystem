package com.app.recharge.payment;

import com.app.recharge.common.CircuitBreakers;
import com.app.recharge.common.OutboundGuard;
import com.app.recharge.payment.spi.PspChargeRequest;
import com.app.recharge.payment.spi.PspClient;
import com.app.recharge.payment.spi.PspRefundRequest;
import com.app.recharge.payment.spi.PspResult;
import com.app.recharge.payment.spi.PspUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Registry of payment providers. Every provider gets its OWN bulkhead, timeout and circuit breaker, so one
 * provider being slow or down cannot affect payments through another. All calls name the provider: a
 * follow-up (lookup, cancel, refund) must always go to the provider that handled the original charge.
 */
@Component
public class PspGateway {

    private record Entry(PspClient client, CircuitBreaker breaker, OutboundGuard guard) { }

    private final Map<String, Entry> providers = new LinkedHashMap<>();

    /**
     * Registers every payment provider found, each with its own circuit breaker, concurrency limit and timeout. Fails
     * at startup if there is none.
     */
    @Autowired
    public PspGateway(List<PspClient> clients,
                      @Value("${app.psp.max-concurrent:50}") int maxConcurrent,
                      @Value("${app.psp.timeout:10s}") Duration timeout) {
        if (clients.isEmpty()) {
            throw new IllegalStateException("No payment provider is configured. Enable a real provider adapter "
                    + "(app.providers.<name>.enabled=true) or, for demo and tests only, set app.psp.mode=mock");
        }
        for (PspClient client : clients) {
            register(client, CircuitBreakers.create("psp-" + client.id(), PspUnavailableException.class),
                    maxConcurrent, timeout);
        }
    }

    /** For tests: one provider with a given circuit breaker. */
    PspGateway(PspClient client, CircuitBreaker breaker) {
        register(client, breaker, 50, Duration.ofSeconds(10));
    }

    /** Adds a provider with its own guard; provider ids must be unique. */
    private void register(PspClient client, CircuitBreaker breaker, int maxConcurrent, Duration timeout) {
        if (providers.containsKey(client.id())) {
            throw new IllegalStateException("Two payment providers use the id '" + client.id() + "'");
        }
        OutboundGuard guard = new OutboundGuard("PSP " + client.id(), breaker, maxConcurrent, timeout,
                PspUnavailableException::new);
        providers.put(client.id(), new Entry(client, breaker, guard));
    }

    /** All registered providers, in registration order. */
    public Collection<PspClient> clients() {
        return providers.values().stream().map(Entry::client).toList();
    }

    /** False while the provider's circuit is open. The router skips such providers for NEW payments. */
    public boolean isAvailable(String providerId) {
        Entry entry = providers.get(providerId);
        return entry != null && entry.breaker().getState() != CircuitBreaker.State.OPEN
                && entry.breaker().getState() != CircuitBreaker.State.FORCED_OPEN;
    }

    /** Charges through the named provider, guarded. Throws PspUnavailableException if the outcome is unknown. */
    public PspResult charge(String providerId, PspChargeRequest request) {
        Entry e = entry(providerId);
        return e.guard().call(() -> e.client().charge(request));
    }

    /** Refunds through the named provider, guarded. */
    public PspResult refund(String providerId, PspRefundRequest request) {
        Entry e = entry(providerId);
        return e.guard().call(() -> e.client().refund(request));
    }

    /** Asks the named provider what it knows about a merchant reference, guarded. */
    public Optional<PspResult> lookup(String providerId, String merchantRef) {
        Entry e = entry(providerId);
        return e.guard().call(() -> e.client().lookup(merchantRef));
    }

    /** Voids a merchant reference at the named provider, guarded. Returns the reference's final state. */
    public PspResult cancel(String providerId, String merchantRef) {
        Entry e = entry(providerId);
        return e.guard().call(() -> e.client().cancel(merchantRef));
    }

    /** A provider that was removed from the deployment while payments still reference it is "unavailable". */
    private Entry entry(String providerId) {
        Entry entry = providers.get(providerId);
        if (entry == null) {
            throw new PspUnavailableException("Payment provider '" + providerId + "' is not configured");
        }
        return entry;
    }
}
