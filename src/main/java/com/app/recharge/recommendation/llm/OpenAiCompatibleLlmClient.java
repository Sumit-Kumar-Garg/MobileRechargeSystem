package com.app.recharge.recommendation.llm;

import com.app.recharge.recommendation.agent.AdvisorProperties;
import com.app.recharge.recommendation.spi.LlmClient;
import com.app.recharge.recommendation.spi.LlmMessage;
import com.app.recharge.recommendation.spi.LlmRequest;
import com.app.recharge.recommendation.spi.LlmResponse;
import com.app.recharge.recommendation.spi.LlmToolCall;
import com.app.recharge.recommendation.spi.LlmToolSpec;
import com.app.recharge.recommendation.spi.LlmUnavailableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Adapter for any model served through the widely used "chat completions with tools" HTTP format
 * (hosted providers, an internal LLM gateway, or a self-hosted model server).
 * The API key stays on the server; the app never talks to the model.
 */
@Component
@ConditionalOnProperty(name = "app.advisor.llm", havingValue = "openai-compatible")
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final RestClient http;
    private final ObjectMapper mapper;
    private final String model;

    /**
     * Builds the HTTP client from the advisor settings (base URL, API key, model, timeouts). Fails at startup if base
     * URL, key or model is missing.
     */
    public OpenAiCompatibleLlmClient(AdvisorProperties props, ObjectMapper mapper) {
        if (props.baseUrl().isBlank() || props.apiKey().isBlank() || props.model().isBlank()) {
            throw new IllegalStateException("app.advisor.llm=openai-compatible needs ADVISOR_LLM_BASE_URL, "
                    + "ADVISOR_LLM_API_KEY and ADVISOR_LLM_MODEL");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout((int) props.callTimeout().toMillis());
        this.http = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + props.apiKey())
                .build();
        this.mapper = mapper;
        this.model = props.model();
    }

    /** For tests: a prepared HTTP client. */
    OpenAiCompatibleLlmClient(RestClient http, ObjectMapper mapper, String model) {
        this.http = http;
        this.mapper = mapper;
        this.model = model;
    }

    @Override
    public String model() {
        return model;
    }

    /**
     * Sends one chat-completions request with the conversation and tools, and parses the reply. HTTP or network errors
     * throw LlmUnavailableException.
     */
    @Override
    public LlmResponse chat(LlmRequest request) {
        JsonNode body;
        try {
            body = http.post().uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(toJson(request))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new LlmUnavailableException("Model call failed: " + e.getClass().getSimpleName(), e);
        }
        return fromJson(body);
    }

    /**
     * Builds the chat-completions request body: model, temperature 0, output limit, messages with their tool calls and
     * tool results, and the tool definitions.
     */
    ObjectNode toJson(LlmRequest request) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("temperature", 0);                      // the same data should give the same advice
        root.put("max_tokens", request.maxOutputTokens());
        ArrayNode messages = root.putArray("messages");
        for (LlmMessage m : request.messages()) {
            ObjectNode node = messages.addObject();
            node.put("role", m.role().name().toLowerCase(java.util.Locale.ROOT));
            node.put("content", m.content());
            if (m.role() == LlmMessage.Role.TOOL) {
                node.put("tool_call_id", m.toolCallId());
            }
            if (!m.toolCalls().isEmpty()) {
                ArrayNode calls = node.putArray("tool_calls");
                for (LlmToolCall call : m.toolCalls()) {
                    ObjectNode c = calls.addObject();
                    c.put("id", call.id());
                    c.put("type", "function");
                    c.putObject("function").put("name", call.name()).put("arguments", call.argumentsJson());
                }
            }
        }
        ArrayNode tools = root.putArray("tools");
        for (LlmToolSpec spec : request.tools()) {
            ObjectNode function = tools.addObject().put("type", "function").putObject("function");
            function.put("name", spec.name());
            function.put("description", spec.description());
            try {
                function.set("parameters", mapper.readTree(spec.parametersJsonSchema()));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Tool " + spec.name() + " has an invalid parameter schema", e);
            }
        }
        return root;
    }

    /**
     * Reads the first choice of a chat-completions reply: text, tool calls and token usage. Throws
     * LlmUnavailableException if there is no message.
     */
    LlmResponse fromJson(JsonNode body) {
        JsonNode message = body == null ? null : body.path("choices").path(0).path("message");
        if (message == null || message.isMissingNode()) {
            throw new LlmUnavailableException("Model answer has no message");
        }
        List<LlmToolCall> calls = new ArrayList<>();
        for (JsonNode c : message.path("tool_calls")) {
            calls.add(new LlmToolCall(c.path("id").asText(), c.path("function").path("name").asText(),
                    c.path("function").path("arguments").asText("{}")));
        }
        String text = message.path("content").isTextual() ? message.path("content").asText() : null;
        return new LlmResponse(text, calls,
                body.path("usage").path("prompt_tokens").asInt(0),
                body.path("usage").path("completion_tokens").asInt(0));
    }
}
