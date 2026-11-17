package com.app.recharge.recommendation.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.recharge.recommendation.agent.AdvisorProperties;
import com.app.recharge.recommendation.spi.LlmMessage;
import com.app.recharge.recommendation.spi.LlmRequest;
import com.app.recharge.recommendation.spi.LlmResponse;
import com.app.recharge.recommendation.spi.LlmToolCall;
import com.app.recharge.recommendation.spi.LlmToolSpec;
import com.app.recharge.recommendation.spi.LlmUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The wire format, checked without a network: what we send and how we read the answer. */
class OpenAiCompatibleLlmClientTest {

    final ObjectMapper mapper = new ObjectMapper();
    final OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(null, mapper, "test-model");

    @Test
    void theRequestCarriesTheConversationToolCallsAndToolSchemas() {
        LlmRequest request = new LlmRequest(List.of(
                LlmMessage.system("rules"),
                LlmMessage.user("go"),
                LlmMessage.assistant(null, List.of(new LlmToolCall("c1", "get_usage_summary", "{}"))),
                LlmMessage.toolResult("c1", "{\"months\":[]}")),
                List.of(new LlmToolSpec("get_usage_summary", "usage", "{\"type\":\"object\",\"properties\":{}}")), 400);

        JsonNode json = client.toJson(request);

        assertThat(json.path("model").asText()).isEqualTo("test-model");
        assertThat(json.path("max_tokens").asInt()).isEqualTo(400);
        assertThat(json.path("messages")).hasSize(4);
        assertThat(json.path("messages").path(0).path("role").asText()).isEqualTo("system");
        JsonNode assistant = json.path("messages").path(2);
        assertThat(assistant.path("tool_calls").path(0).path("id").asText()).isEqualTo("c1");
        assertThat(assistant.path("tool_calls").path(0).path("type").asText()).isEqualTo("function");
        assertThat(assistant.path("tool_calls").path(0).path("function").path("name").asText()).isEqualTo("get_usage_summary");
        JsonNode tool = json.path("messages").path(3);
        assertThat(tool.path("role").asText()).isEqualTo("tool");
        assertThat(tool.path("tool_call_id").asText()).isEqualTo("c1");
        assertThat(json.path("tools").path(0).path("function").path("parameters").path("type").asText()).isEqualTo("object");
    }

    @Test
    void anAnswerWithToolCallsIsRead() throws Exception {
        JsonNode body = mapper.readTree("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"c9\",\"type\":\"function\",\"function\":{\"name\":\"submit_recommendation\","
                + "\"arguments\":\"{\\\"planCode\\\":\\\"M-28\\\"}\"}}]}}],"
                + "\"usage\":{\"prompt_tokens\":321,\"completion_tokens\":45}}");

        LlmResponse response = client.fromJson(body);

        assertThat(response.text()).isNull();
        assertThat(response.toolCalls()).containsExactly(
                new LlmToolCall("c9", "submit_recommendation", "{\"planCode\":\"M-28\"}"));
        assertThat(response.inputTokens()).isEqualTo(321);
        assertThat(response.outputTokens()).isEqualTo(45);
    }

    @Test
    void aTextAnswerIsRead() throws Exception {
        LlmResponse response = client.fromJson(mapper.readTree(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hello\"}}]}"));

        assertThat(response.text()).isEqualTo("hello");
        assertThat(response.toolCalls()).isEmpty();
    }

    @Test
    void anAnswerWithoutAMessageIsAnOutageNotACrash() throws Exception {
        assertThatThrownBy(() -> client.fromJson(mapper.readTree("{\"error\":{\"message\":\"overloaded\"}}")))
                .isInstanceOf(LlmUnavailableException.class);
        assertThatThrownBy(() -> client.fromJson(null)).isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void startingWithoutUrlKeyOrModelFailsAtStartup() {
        AdvisorProperties incomplete = new AdvisorProperties("openai-compatible", 6, Duration.ofSeconds(20), 10, 400,
                Duration.ofHours(24), 5, "https://llm.example/v1", "", "some-model");

        assertThatThrownBy(() -> new OpenAiCompatibleLlmClient(incomplete, mapper))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ADVISOR_LLM_API_KEY");
    }
}
