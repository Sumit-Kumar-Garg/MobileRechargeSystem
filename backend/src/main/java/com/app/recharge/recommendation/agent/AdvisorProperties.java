package com.app.recharge.recommendation.agent;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the recharge advisor agent.
 *
 * @param llm              which model adapter to use: none (agent off, rules only) | scripted (demo) | openai-compatible
 * @param maxSteps         upper bound of model turns per run; stops loops and bounds cost
 * @param callTimeout      hard timeout of one model turn
 * @param maxConcurrent    model calls in flight at the same time
 * @param maxOutputTokens  output limit per model turn
 * @param adviceTtl        how long a stored advice is served before the agent is asked again
 * @param runsPerUserPerDay cost and abuse limit: agent runs one user can cause per day
 * @param baseUrl          openai-compatible only: API root, for example https://llm.internal.example/v1
 * @param apiKey           openai-compatible only: secret, from the environment, never sent to clients
 * @param model            openai-compatible only: model name
 */
@ConfigurationProperties("app.advisor")
public record AdvisorProperties(
        @DefaultValue("none") String llm,
        @DefaultValue("6") int maxSteps,
        @DefaultValue("20s") Duration callTimeout,
        @DefaultValue("10") int maxConcurrent,
        @DefaultValue("400") int maxOutputTokens,
        @DefaultValue("24h") Duration adviceTtl,
        @DefaultValue("5") int runsPerUserPerDay,
        @DefaultValue("") String baseUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue("") String model) { }
