package com.app.recharge.recommendation.spi;

/** The model asks for a tool to be run. {@code argumentsJson} is a JSON object written by the model: untrusted. */
public record LlmToolCall(String id, String name, String argumentsJson) { }
