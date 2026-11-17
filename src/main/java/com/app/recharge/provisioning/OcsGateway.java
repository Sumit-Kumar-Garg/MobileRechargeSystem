package com.app.recharge.provisioning;

import com.app.recharge.common.CircuitBreakers;
import com.app.recharge.common.OutboundGuard;
import com.app.recharge.order.FulfilmentDirectory;
import com.app.recharge.provisioning.spi.OcsClient;
import com.app.recharge.provisioning.spi.OcsRequest;
import com.app.recharge.provisioning.spi.OcsResult;
import com.app.recharge.provisioning.spi.OcsUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Registry of fulfilment systems, one per plan provider, each behind its own bulkhead, timeout and circuit
 * breaker. An order is always fulfilled (and cancelled) by the provider that supplies its plan.
 */
@Component
public class OcsGateway implements FulfilmentDirectory {

    private record Entry(OcsClient client, OutboundGuard guard) { }

    private final Map<String, Entry> providers = new HashMap<>();

    /**
     * Registers every fulfilment system found, each with its own circuit breaker, concurrency limit and timeout. Fails
     * at startup if there is none.
     */
    @Autowired
    public OcsGateway(List<OcsClient> clients,
                      @Value("${app.ocs.max-concurrent:30}") int maxConcurrent,
                      @Value("${app.ocs.timeout:10s}") Duration timeout) {
        if (clients.isEmpty()) {
            throw new IllegalStateException("No fulfilment system is configured. Enable a real provider adapter "
                    + "(app.providers.<name>.enabled=true) or, for demo and tests only, set app.ocs.mode=mock");
        }
        for (OcsClient client : clients) {
            register(client, CircuitBreakers.create("ocs-" + client.providerId(), OcsUnavailableException.class),
                    maxConcurrent, timeout);
        }
    }

    /** For tests: one provider with a given circuit breaker. */
    OcsGateway(OcsClient client, CircuitBreaker breaker, int maxConcurrent, Duration timeout) {
        register(client, breaker, maxConcurrent, timeout);
    }

    /** Adds a fulfilment system with its own guard; only one system per plan provider. */
    private void register(OcsClient client, CircuitBreaker breaker, int maxConcurrent, Duration timeout) {
        if (providers.containsKey(client.providerId())) {
            throw new IllegalStateException("Two fulfilment systems serve the plan provider '" + client.providerId() + "'");
        }
        providers.put(client.providerId(), new Entry(client,
                new OutboundGuard("OCS " + client.providerId(), breaker, maxConcurrent, timeout, OcsUnavailableException::new)));
    }

    /** True if a fulfilment system is registered for this plan provider. */
    @Override
    public boolean canFulfil(String providerId) {
        return providers.containsKey(providerId);
    }

    /**
     * Activates the plan through the provider's fulfilment system, guarded. An unknown provider is a definitive
     * rejection; a technical problem throws OcsUnavailableException.
     */
    public OcsResult provision(String providerId, OcsRequest request) {
        Entry entry = providers.get(providerId);
        if (entry == null) {
            // Nobody can activate this plan: a definitive "no", so the customer is refunded at once.
            return OcsResult.rejected("fulfilment_provider_unknown");
        }
        return entry.guard().call(() -> entry.client().provision(request));
    }

    /**
     * Cancels the reference at the provider's fulfilment system, guarded. Does nothing for an unknown provider, since
     * nothing could have been activated there.
     */
    public void cancel(String providerId, String referenceId) {
        Entry entry = providers.get(providerId);
        if (entry == null) {
            return;                 // nothing could have been provisioned
        }
        entry.guard().call(() -> {
            entry.client().cancel(referenceId);
            return Boolean.TRUE;
        });
    }
}
