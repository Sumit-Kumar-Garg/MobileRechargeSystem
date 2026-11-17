package com.app.recharge.payment;

import com.app.recharge.common.BusinessException;
import com.app.recharge.common.ErrorCode;
import com.app.recharge.payment.spi.PspClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Chooses the provider for a NEW payment attempt:
 * providers that support the currency and method, in the configured priority order, skipping any whose
 * circuit is open (failover before money moves). Once an attempt has been sent to a provider it is never
 * moved to another one: an unknown outcome is resolved with the same provider by reconciliation.
 */
@Component
public class PaymentRouter {

    public static final String DEFAULT_METHOD = "card";

    private final PspGateway gateway;
    private final List<String> priority;

    public PaymentRouter(PspGateway gateway,
                         @Value("${app.payment.provider-priority:}") List<String> priority) {
        this.gateway = gateway;
        this.priority = priority == null ? List.of() : priority;
    }

    /**
     * Picks the provider for a new payment: the highest-priority provider that supports the currency and method and
     * whose circuit is closed. If all such circuits are open, the preferred one is still returned. Throws
     * PAYMENT_METHOD_UNAVAILABLE if no provider supports it.
     */
    public String route(String currency, String method) {
        List<PspClient> supporting = candidates(currency, method);
        if (supporting.isEmpty()) {
            throw new BusinessException(ErrorCode.PAYMENT_METHOD_UNAVAILABLE,
                    "This payment method is not available for this order");
        }
        return supporting.stream()
                .filter(c -> gateway.isAvailable(c.id()))
                .findFirst()
                .orElse(supporting.get(0))         // all circuits open: try the preferred one, outcome "unknown"
                .id();
    }

    /** Methods that at least one provider offers for this currency. */
    public List<String> methods(String currency) {
        TreeSet<String> methods = new TreeSet<>();
        for (PspClient client : gateway.clients()) {
            for (String method : client.methods()) {
                if (client.supports(currency, method)) {
                    methods.add(method);
                }
            }
        }
        return new ArrayList<>(methods);
    }

    /** Providers supporting the currency and method, in configured priority order (unlisted providers last). */
    private List<PspClient> candidates(String currency, String method) {
        return gateway.clients().stream()
                .filter(c -> c.supports(currency, method))
                .sorted(Comparator.comparingInt(c -> rank(c.id())))
                .toList();
    }

    /** Position of the provider in the configured priority list; unlisted providers rank last. */
    private int rank(String providerId) {
        int index = priority.indexOf(providerId);
        return index < 0 ? Integer.MAX_VALUE : index;
    }
}
