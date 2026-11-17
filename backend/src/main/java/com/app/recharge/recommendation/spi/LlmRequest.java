package com.app.recharge.recommendation.spi;

import java.util.List;

/** The full conversation so far plus the tools the model may call in this turn. */
public record LlmRequest(List<LlmMessage> messages, List<LlmToolSpec> tools, int maxOutputTokens) {

    public LlmRequest {
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
    }
}
