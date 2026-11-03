package com.guidewire.fnol.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Gemini with function calling, text only: it sees the instructions and the tools' redacted outputs, never a photo or raw personal
 * data. Never in lite mode, which promises no external model. With no key, or in lite mode, it fails and the agent uses the template.
 */
@Component
@ConditionalOnProperty(name = "fnol.drafting-agent.enabled", havingValue = "true")
public class GeminiDraftingModel implements DraftingModel {

    private static final String URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestTemplate rest;
    private final String apiKey;
    private final String model;
    private final boolean liteMode;

    public GeminiDraftingModel(RestTemplateBuilder builder,
                               @Value("${gemini.api-key:}") String apiKey,
                               @Value("${gemini.vision.model:gemini-2.5-flash}") String model,
                               @Value("${fnol.lite-mode:false}") boolean liteMode,
                               @Value("${fnol.drafting-agent.max-seconds:8}") long maxSeconds) {
        this.rest = builder.setConnectTimeout(Duration.ofSeconds(3)).setReadTimeout(Duration.ofSeconds(maxSeconds)).build();
        this.apiKey = apiKey;
        this.model = model;
        this.liteMode = liteMode;
    }

    @Override
    public Turn next(String instructions, List<ToolSpec> tools, List<Step> steps) {
        if (liteMode) {
            throw new IllegalStateException("no external model in lite mode");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("no Gemini key");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", apiKey);     // in a header, never in the URL, which ends up in logs
        String body = rest.postForObject(URL.formatted(model), new HttpEntity<>(request(instructions, tools, steps), headers), String.class);
        try {
            return parse(MAPPER.readTree(body));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("unreadable reply", e);
        }
    }

    static Map<String, Object> request(String instructions, List<ToolSpec> tools, List<Step> steps) {
        List<Map<String, Object>> contents = new ArrayList<>();
        contents.add(Map.of("role", "user", "parts", List.of(Map.of("text", instructions))));
        for (Step step : steps) {
            contents.add(Map.of("role", "model", "parts", List.of(Map.of("functionCall", Map.of("name", step.tool(), "args", Map.of())))));
            contents.add(Map.of("role", "user", "parts", List.of(Map.of("functionResponse",
                    Map.of("name", step.tool(), "response", Map.of("result", step.result()))))));
        }
        List<Map<String, Object>> declarations = tools.stream()
                .map(t -> Map.<String, Object>of("name", t.name(), "description", t.description())).toList();
        return Map.of("contents", contents, "tools", List.of(Map.of("functionDeclarations", declarations)),
                "generationConfig", Map.of("temperature", 0.2, "thinkingConfig", Map.of("thinkingBudget", 0)));
    }

    static Turn parse(JsonNode reply) {
        StringBuilder text = new StringBuilder();
        for (JsonNode part : reply.path("candidates").path(0).path("content").path("parts")) {
            if (part.has("functionCall")) {
                return Turn.call(part.path("functionCall").path("name").asText());
            }
            text.append(part.path("text").asText(""));
        }
        return Turn.finish(text.toString());
    }
}
