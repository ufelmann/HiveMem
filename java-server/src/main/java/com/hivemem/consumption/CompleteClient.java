package com.hivemem.consumption;

import com.hivemem.queen.QueenProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** Calls Vistierie's text completion endpoint (POST /llm/complete) for the reassembly's
 *  text-only mailing-assembly pass. Model selection stays with the routing rule (purpose). */
@Component
@ConditionalOnProperty(name = "hivemem.queen.enabled", havingValue = "true")
public class CompleteClient {

    private final RestClient client;
    private final String tenantToken;
    private final String agentName;
    private final ConsumptionProperties consumption;

    public CompleteClient(RestClient.Builder builder, QueenProperties props,
                          ConsumptionProperties consumption) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(props.getCallTimeoutSeconds() * 1000);
        rf.setReadTimeout(props.getCallTimeoutSeconds() * 1000);
        this.client = builder.baseUrl(props.getVistierieBaseUrl()).requestFactory(rf).build();
        this.tenantToken = props.getVistierieToken();
        this.agentName = props.getDocumentSeparatorAgent();
        this.consumption = consumption;
    }

    /** Send a text-only prompt; return the model's raw text (caller parses JSON). */
    public String complete(String realm, String prompt) {
        Map<String, Object> body = new HashMap<>();
        body.put("agent_name", agentName);
        body.put("purpose", consumption.getReassemblyPurpose());
        if (realm != null) body.put("realm", realm);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        body.put("max_tokens", consumption.getReassemblyMaxTokens());

        Resp r = client.post().uri("/llm/complete")
                .header("Authorization", "Bearer " + tenantToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(Resp.class);
        return r == null ? null : r.text();
    }

    /** Send a text-only prompt the model MUST answer by calling {@code toolName}, and return that
     *  call's input. Delivering the payload as tool input means the API layer serializes it, so the
     *  model cannot spend its budget on prose before the answer — the failure that collapsed a
     *  41-page batch into one document on 2026-08-15.
     *  @return the tool call's input, or null when the response carries no matching tool_use block
     *          (the caller then falls back to parsing {@link #complete}'s text). */
    public JsonNode completeWithTool(String realm, String prompt, String toolName,
                                     String toolDescription, Map<String, Object> inputSchema) {
        Map<String, Object> body = new HashMap<>();
        body.put("agent_name", agentName);
        body.put("purpose", consumption.getReassemblyPurpose());
        if (realm != null) body.put("realm", realm);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        body.put("max_tokens", consumption.getReassemblyMaxTokens());
        body.put("tools", List.of(Map.of(
                "name", toolName,
                "description", toolDescription,
                "input_schema", inputSchema)));
        body.put("tool_choice", Map.of("type", "tool", "name", toolName));

        Resp r = client.post().uri("/llm/complete")
                .header("Authorization", "Bearer " + tenantToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(Resp.class);
        return r == null ? null : firstToolInput(r.content_blocks(), toolName);
    }

    private static JsonNode firstToolInput(JsonNode blocks, String toolName) {
        if (blocks == null || !blocks.isArray()) return null;
        for (JsonNode b : blocks) {
            if ("tool_use".equals(b.path("type").asString(null))
                    && toolName.equals(b.path("name").asString(null))) {
                JsonNode input = b.path("input");
                return input.isMissingNode() || input.isNull() ? null : input;
            }
        }
        return null;
    }

    /** Subset of Vistierie's LlmResponse. */
    record Resp(String text, JsonNode content_blocks) {}
}
