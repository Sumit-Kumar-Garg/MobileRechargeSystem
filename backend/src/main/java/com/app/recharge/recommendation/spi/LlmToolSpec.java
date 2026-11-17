package com.app.recharge.recommendation.spi;

/** A tool the model may call. {@code parametersJsonSchema} is a JSON Schema object describing its arguments. */
public record LlmToolSpec(String name, String description, String parametersJsonSchema) { }
