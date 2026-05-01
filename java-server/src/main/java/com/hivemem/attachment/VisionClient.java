package com.hivemem.attachment;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class VisionClient {

    public record VisionResult(String description, int inputTokens, int outputTokens) {}

    public static class OversizeImageException extends RuntimeException {
        public OversizeImageException(String msg) { super(msg); }
    }

    private static final Set<String> SUPPORTED_MIME = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp");

    private static final String DESCRIBE_PROMPT =
            "Beschreibe das Bild kurz aber präzise: was ist zu sehen, welcher Bildtyp "
                    + "(Foto, Screenshot, Whiteboard, Diagramm), erkennbarer Text, Kontextrelevantes. "
                    + "Antworte als zusammenhängender Text, max 200 Wörter.";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AttachmentProperties props;
    private final RestClient client;

    @Autowired
    public VisionClient(AttachmentProperties props, RestClient.Builder builder) {
        this(props, builder, true);
    }

    VisionClient(AttachmentProperties props, RestClient.Builder builder, boolean configureRequestFactory) {
        this.props = props;
        if (configureRequestFactory) {
            int timeoutMs = props.getVisionTimeoutSeconds() * 1000;
            SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
            rf.setConnectTimeout(timeoutMs);
            rf.setReadTimeout(timeoutMs);
            builder = builder.requestFactory(rf);
        }
        this.client = builder.build();
    }

    public boolean isEnabled() {
        return props.getAnthropicApiKey() != null && !props.getAnthropicApiKey().isBlank();
    }

    /**
     * Throws OversizeImageException when bytes exceed configured cap.
     * Throws IllegalArgumentException for unsupported MIME types.
     * Lets HttpClientErrorException.TooManyRequests bubble for 429 (caller backoff).
     */
    public VisionResult describe(byte[] imageBytes, String mimeType) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("imageBytes empty");
        }
        if (imageBytes.length > props.getVisionMaxInputBytes()) {
            throw new OversizeImageException("image " + imageBytes.length
                    + " bytes exceeds cap " + props.getVisionMaxInputBytes());
        }
        if (!SUPPORTED_MIME.contains(mimeType)) {
            throw new IllegalArgumentException("Unsupported image mime: " + mimeType);
        }

        String base64 = Base64.getEncoder().encodeToString(imageBytes);

        Map<String, Object> body = Map.of(
                "model", props.getVisionModel(),
                "max_tokens", 600,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "image",
                                        "source", Map.of(
                                                "type", "base64",
                                                "media_type", mimeType,
                                                "data", base64)),
                                Map.of("type", "text", "text", DESCRIBE_PROMPT)
                        )))
        );

        JsonNode resp = client.post()
                .uri("https://api.anthropic.com/v1/messages")
                .header("x-api-key", props.getAnthropicApiKey())
                .header("anthropic-version", "2023-06-01")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        if (resp == null) throw new IllegalStateException("Anthropic returned null");
        String text = resp.path("content").path(0).path("text").asText();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("Anthropic returned empty text");
        }
        int inputTokens = resp.path("usage").path("input_tokens").asInt(0);
        int outputTokens = resp.path("usage").path("output_tokens").asInt(0);
        return new VisionResult(text.strip(), inputTokens, outputTokens);
    }
}
