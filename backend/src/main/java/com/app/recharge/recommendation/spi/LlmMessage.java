package com.app.recharge.recommendation.spi;

import java.util.List;

/**
 * One entry of the conversation.
 * SYSTEM and USER carry text. ASSISTANT carries text and/or the tool calls it asked for.
 * TOOL carries the result of one tool call and names that call in {@code toolCallId}.
 */
public record LlmMessage(Role role, String content, List<LlmToolCall> toolCalls, String toolCallId) {

    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    public LlmMessage {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /** A system instruction message. */
    public static LlmMessage system(String text) {
        return new LlmMessage(Role.SYSTEM, text, List.of(), null);
    }

    /** A user message. */
    public static LlmMessage user(String text) {
        return new LlmMessage(Role.USER, text, List.of(), null);
    }

    /** A model reply: text and/or the tool calls it asked for. */
    public static LlmMessage assistant(String text, List<LlmToolCall> toolCalls) {
        return new LlmMessage(Role.ASSISTANT, text, toolCalls, null);
    }

    /** The JSON result of one tool call, linked to that call by its id. */
    public static LlmMessage toolResult(String toolCallId, String resultJson) {
        return new LlmMessage(Role.TOOL, resultJson, List.of(), toolCallId);
    }
}
