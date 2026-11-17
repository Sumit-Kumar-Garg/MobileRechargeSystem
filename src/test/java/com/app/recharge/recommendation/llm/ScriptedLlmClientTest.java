package com.app.recharge.recommendation.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.recharge.recommendation.agent.AdvisorToolbox;
import com.app.recharge.recommendation.spi.LlmMessage;
import com.app.recharge.recommendation.spi.LlmRequest;
import com.app.recharge.recommendation.spi.LlmResponse;
import com.app.recharge.recommendation.spi.LlmToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScriptedLlmClientTest {

    static final String PLANS = "{\"plans\":["
            + "{\"code\":\"S-28\",\"priceMinor\":1000,\"dataMb\":3000},"
            + "{\"code\":\"M-28\",\"priceMinor\":1500,\"dataMb\":10000},"
            + "{\"code\":\"L-28\",\"priceMinor\":2500,\"dataMb\":30000}]}";

    final ScriptedLlmClient client = new ScriptedLlmClient(new ObjectMapper());

    private LlmResponse secondTurn(String language, String usage, String history) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system("Explain in " + language + "."));
        messages.add(LlmMessage.user("Recommend the best plan for this customer."));
        LlmResponse first = client.chat(new LlmRequest(messages, List.of(), 400));
        messages.add(LlmMessage.assistant(null, first.toolCalls()));
        for (LlmToolCall call : first.toolCalls()) {
            String result = switch (call.name()) {
                case AdvisorToolbox.USAGE -> usage;
                case AdvisorToolbox.HISTORY -> history;
                default -> PLANS;
            };
            messages.add(LlmMessage.toolResult(call.id(), result));
        }
        return client.chat(new LlmRequest(messages, List.of(), 400));
    }

    @Test
    void firstItAsksForAllThreeDataTools() {
        LlmResponse first = client.chat(new LlmRequest(
                List.of(LlmMessage.system("x"), LlmMessage.user("y")), List.of(), 400));

        assertThat(first.toolCalls()).extracting(LlmToolCall::name)
                .containsExactly(AdvisorToolbox.USAGE, AdvisorToolbox.HISTORY, AdvisorToolbox.PLANS);
    }

    @Test
    void withUsageItSubmitsTheCheapestPlanThatCoversIt() {
        LlmResponse second = secondTurn("English",
                "{\"months\":[{\"dataMbUsed\":8000},{\"dataMbUsed\":9000}]}", "{\"recharges\":[]}");

        assertThat(second.toolCalls()).hasSize(1);
        assertThat(second.toolCalls().get(0).name()).isEqualTo(AdvisorToolbox.SUBMIT);
        assertThat(second.toolCalls().get(0).argumentsJson()).contains("\"planCode\":\"M-28\"").contains("8.5 GB");
    }

    @Test
    void withoutUsageItRepeatsTheLastPurchaseAndAnswersInGermanWhenAsked() {
        LlmResponse second = secondTurn("German", "{\"months\":[]}", "{\"recharges\":[{\"planCode\":\"L-28\"}]}");

        assertThat(second.toolCalls().get(0).argumentsJson()).contains("\"planCode\":\"L-28\"").contains("Tarif");
    }

    @Test
    void withNothingKnownItPicksTheMiddlePlan() {
        LlmResponse second = secondTurn("English", "{\"months\":[]}", "{\"recharges\":[]}");

        assertThat(second.toolCalls().get(0).argumentsJson()).contains("\"planCode\":\"M-28\"");
    }
}
