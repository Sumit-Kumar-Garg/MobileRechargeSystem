package com.app.recharge.recommendation.spi;

import java.util.List;

/** One model turn: tool calls to run, or text. Token counts are 0 when the provider does not report them. */
public record LlmResponse(String text, List<LlmToolCall> toolCalls, int inputTokens, int outputTokens) {

    public LlmResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /** A text-only answer, without token counts. */
    public static LlmResponse text(String text) {
        return new LlmResponse(text, List.of(), 0, 0);
    }

    /** An answer that only asks for tool calls, without token counts. */
    public static LlmResponse calls(List<LlmToolCall> toolCalls) {
        return new LlmResponse(null, toolCalls, 0, 0);
    }
}
