package com.app.recharge.recommendation.agent;

import com.app.recharge.common.CircuitBreakers;
import com.app.recharge.common.OutboundGuard;
import com.app.recharge.recommendation.spi.LlmClient;
import com.app.recharge.recommendation.spi.LlmRequest;
import com.app.recharge.recommendation.spi.LlmResponse;
import com.app.recharge.recommendation.spi.LlmUnavailableException;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The only way the agent reaches a model. Adds the same protection every other external dependency has:
 * concurrency limit, hard timeout, circuit breaker. With no model configured the agent is simply off.
 */
@Component
public class LlmGateway {

    private final LlmClient client;
    private final OutboundGuard guard;

    /** Wraps the configured model client, if any, with a concurrency limit, timeout and circuit breaker. */
    public LlmGateway(ObjectProvider<LlmClient> client, AdvisorProperties props) {
        this.client = client.getIfAvailable();
        this.guard = new OutboundGuard("LLM",
                CircuitBreakers.create("llm", LlmUnavailableException.class),
                props.maxConcurrent(), props.callTimeout(), LlmUnavailableException::new);
    }

    /** True when a model client is configured. */
    public boolean isConfigured() {
        return client != null;
    }

    /** The configured model's name, if any. */
    public Optional<String> model() {
        return Optional.ofNullable(client).map(LlmClient::model);
    }

    /**
     * One guarded model turn. Throws LlmUnavailableException if no model is configured, or if the call fails, times out
     * or is refused by the guard.
     */
    public LlmResponse chat(LlmRequest request) {
        if (client == null) {
            throw new LlmUnavailableException("No language model is configured");
        }
        return guard.call(() -> client.chat(request));
    }
}
