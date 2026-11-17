package com.app.recharge.recommendation.spi;

/**
 * Contract for a language model that can call tools. The recommendation agent only knows this interface;
 * which vendor or self-hosted model answers is an adapter and a configuration choice.
 * Implementations must be stateless: the whole conversation is passed on every call.
 */
public interface LlmClient {

    /** Shown in logs, metrics and the recommendation's model version, for example "gpt-4o-mini". */
    String model();

    /**
     * One model turn. Returns either tool calls the agent must run, or plain text.
     *
     * @throws LlmUnavailableException when the model cannot be reached, is too slow or answers with an error
     */
    LlmResponse chat(LlmRequest request);
}
