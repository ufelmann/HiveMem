package com.hivemem.summarize;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AnthropicSummarizer {

    private static final String SYSTEM_PROMPT = """
            You distill cells in HiveMem (a personal knowledge graph). Given the full content
            of a cell, produce a structured summary that captures its essence so the cell can
            be found by semantic search and skimmed at a glance.

            Respond with ONLY this JSON, no surrounding prose:
            {
              "summary": "1-2 sentences, ≤ 250 chars, capturing the cell's purpose",
              "key_points": ["3-5 bullets, each ≤ 80 chars"],
              "insight": "1 sentence ≤ 200 chars, the non-obvious takeaway",
              "tags": ["up to 5 lowercase kebab-case tags"]
            }

            If content is too sparse for key_points or insight, return [] / null. Always
            provide a summary.
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient client;
    private final String apiKey;
    private final String model;
    private final int maxInputChars;

    public AnthropicSummarizer(RestClient.Builder builder, String apiKey, String model,
                               int timeoutSeconds, int maxInputChars) {
        this(builder, apiKey, model, timeoutSeconds, maxInputChars, true);
    }

    AnthropicSummarizer(RestClient.Builder builder, String apiKey, String model,
                        int timeoutSeconds, int maxInputChars, boolean configureRequestFactory) {
        if (configureRequestFactory) {
            SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
            rf.setConnectTimeout(timeoutSeconds * 1000);
            rf.setReadTimeout(timeoutSeconds * 1000);
            builder = builder.requestFactory(rf);
        }
        this.client = builder.build();
        this.apiKey = apiKey;
        this.model = model;
        this.maxInputChars = maxInputChars;
    }

    public SummaryResult summarize(String content) {
        String input = (content.length() > maxInputChars)
                ? content.substring(0, maxInputChars) + "\n\n[truncated]"
                : content;

        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", 600,
                "system", SYSTEM_PROMPT,
                "messages", List.of(Map.of("role", "user", "content", input))
        );

        JsonNode resp = client.post()
                .uri("https://api.anthropic.com/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        if (resp == null) throw new IllegalStateException("Anthropic returned null body");

        String text = resp.path("content").path(0).path("text").asText();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("Anthropic returned empty content");
        }

        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse summarizer JSON: " + text, e);
        }

        String summary = parsed.path("summary").asText(null);
        List<String> keyPoints = asStringList(parsed.path("key_points"));
        String insight = parsed.hasNonNull("insight") ? parsed.path("insight").asText() : null;
        List<String> tags = asStringList(parsed.path("tags"));

        int inputTokens = resp.path("usage").path("input_tokens").asInt(0);
        int outputTokens = resp.path("usage").path("output_tokens").asInt(0);

        return new SummaryResult(summary, keyPoints, insight, tags, inputTokens, outputTokens);
    }

    private static List<String> asStringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode n : node) out.add(n.asText());
        }
        return out;
    }
}
