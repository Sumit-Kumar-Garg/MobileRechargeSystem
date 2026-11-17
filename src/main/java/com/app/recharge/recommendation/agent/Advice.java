package com.app.recharge.recommendation.agent;

import com.app.recharge.catalog.PlanView;

/** A validated result of one agent run. */
public record Advice(PlanView plan, String explanation, String model, int steps, int inputTokens, int outputTokens) { }
