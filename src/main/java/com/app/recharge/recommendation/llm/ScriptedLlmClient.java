package com.app.recharge.recommendation.llm;

import com.app.recharge.recommendation.agent.AdvisorToolbox;
import com.app.recharge.recommendation.spi.LlmClient;
import com.app.recharge.recommendation.spi.LlmMessage;
import com.app.recharge.recommendation.spi.LlmRequest;
import com.app.recharge.recommendation.spi.LlmResponse;
import com.app.recharge.recommendation.spi.LlmToolCall;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * A stand-in for a real model, for the demo profile and tests: no network, no key, the same answer every time.
 * It behaves like a model from the agent's point of view: first it asks for the three data tools, then it
 * reads their results from the conversation and submits a plan. It is NOT intelligent; it exists so the whole
 * agent path (tools, validation, storage, API, app) can be shown and tested without a model provider.
 */
@Component
@ConditionalOnProperty(name = "app.advisor.llm", havingValue = "scripted")
public class ScriptedLlmClient implements LlmClient {

    private final ObjectMapper mapper;

    public ScriptedLlmClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String model() {
        return "scripted-demo";
    }

    /**
     * Plays a fixed script: if the plans have not been read yet, asks for the usage, history and plans tools; otherwise
     * submits a plan chosen from those tool results.
     */
    @Override
    public LlmResponse chat(LlmRequest request) {
        Map<String, String> toolNameByCallId = new HashMap<>();
        Map<String, JsonNode> results = new HashMap<>();
        for (LlmMessage m : request.messages()) {
            m.toolCalls().forEach(c -> toolNameByCallId.put(c.id(), c.name()));
            if (m.role() == LlmMessage.Role.TOOL) {
                results.put(toolNameByCallId.getOrDefault(m.toolCallId(), ""), parse(m.content()));
            }
        }
        if (!results.containsKey(AdvisorToolbox.PLANS)) {
            return LlmResponse.calls(List.of(
                    new LlmToolCall("call_usage", AdvisorToolbox.USAGE, "{}"),
                    new LlmToolCall("call_history", AdvisorToolbox.HISTORY, "{}"),
                    new LlmToolCall("call_plans", AdvisorToolbox.PLANS, "{}")));
        }
        boolean german = request.messages().get(0).content().contains("in German");
        return LlmResponse.calls(List.of(new LlmToolCall("call_submit", AdvisorToolbox.SUBMIT,
                decide(results.get(AdvisorToolbox.USAGE), results.get(AdvisorToolbox.HISTORY),
                        results.get(AdvisorToolbox.PLANS), german))));
    }

    /**
     * Picks the plan and explanation: the cheapest plan covering average usage plus 10% (or the biggest plan), else the
     * last plan bought, else the middle plan. Returns the submit arguments as JSON.
     */
    private String decide(JsonNode usage, JsonNode history, JsonNode plans, boolean german) {
        double total = 0;
        int months = 0;
        for (JsonNode m : usage == null ? mapper.createArrayNode() : usage.path("months")) {
            total += m.path("dataMbUsed").asInt(0);
            months++;
        }
        double average = months == 0 ? 0 : total / months;

        String code = null;
        String explanation;
        if (average > 0) {
            int bestPrice = Integer.MAX_VALUE;
            String biggest = null;
            int biggestData = -1;
            for (JsonNode p : plans.path("plans")) {
                int data = p.path("dataMb").asInt(0);
                int price = p.path("priceMinor").asInt(Integer.MAX_VALUE);
                if (data >= average * 1.1 && price < bestPrice) {
                    bestPrice = price;
                    code = p.path("code").asText();
                }
                if (data > biggestData) {
                    biggestData = data;
                    biggest = p.path("code").asText();
                }
            }
            code = code != null ? code : biggest;
            String gb = String.format(java.util.Locale.ROOT, "%.1f", average / 1000.0);
            explanation = german
                    ? "Du nutzt im Schnitt etwa " + gb + " GB pro Monat. Dieser Tarif deckt das mit etwas Reserve ab."
                    : "You use about " + gb + " GB a month on average. This plan covers that with a little headroom.";
        } else if (history != null && history.path("recharges").size() > 0) {
            code = history.path("recharges").path(0).path("planCode").asText();
            explanation = german
                    ? "Diesen Tarif hast du zuletzt gebucht. Er passt weiterhin zu deinen Aufladungen."
                    : "This is the plan you bought last time. It still matches how you recharge.";
        } else {
            JsonNode all = plans.path("plans");
            code = all.path(all.size() / 2).path("code").asText();
            explanation = german
                    ? "Wir kennen deine Nutzung noch nicht. Dieser Tarif ist ein guter Einstieg."
                    : "We do not know your usage yet. This plan is a good place to start.";
        }
        return mapper.createObjectNode().put("planCode", code).put("explanation", explanation).toString();
    }

    /** Parses a tool result as JSON, or returns an empty object if it is not valid JSON. */
    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            return mapper.createObjectNode();
        }
    }
}
