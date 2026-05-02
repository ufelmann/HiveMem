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

    public record ImageDescriptionResult(
            String subType, String content, int inputTokens, int outputTokens) {}

    public static class OversizeImageException extends RuntimeException {
        public OversizeImageException(String msg) { super(msg); }
    }

    private static final Set<String> SUPPORTED_MIME = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp");

    private static final String DESCRIBE_PROMPT =
            "Beschreibe das Bild kurz aber präzise: was ist zu sehen, welcher Bildtyp "
                    + "(Foto, Screenshot, Whiteboard, Diagramm), erkennbarer Text, Kontextrelevantes. "
                    + "Antworte als zusammenhängender Text, max 200 Wörter.";

    private static final String TRANSCRIBE_PROMPT =
            "Du transkribierst eine gescannte Dokumentseite. Gib den vollständigen "
                    + "sichtbaren Text exakt so wieder, wie er auf der Seite steht, in der "
                    + "Lese-Reihenfolge. Übernimm Tabellen als Markdown-Tabellen. Lass keine "
                    + "Information weg. Antworte NUR mit dem transkribierten Text, ohne "
                    + "Vorwort, ohne Kommentar, ohne Markdown-Code-Fences.";


    private static final String IMAGE_CLASSIFY_PROMPT =
            "Analysiere das Bild und liefere strukturiertes Ergebnis als JSON.\n\n"
            + "1) Identifiziere den Bildtyp (sub_type), genau einer von:\n"
            + "   - \"whiteboard_photo\": Foto eines Whiteboards/Notiz mit Text, "
            + "Pfeilen, Skizzen.\n"
            + "   - \"document_scan\": Foto/Scan eines Dokuments (Brief, Rechnung, "
            + "Vertrag) mit erkennbarem Volltext in Lese-Reihenfolge.\n"
            + "   - \"photo_general\": alles andere (Foto, Screenshot, Grafik, "
            + "Person, Objekt).\n\n"
            + "2) Liefere \"content\" abhängig vom sub_type:\n"
            + "   - whiteboard_photo: extrahiere ALLEN sichtbaren Text und "
            + "beschreibe Struktur (Hierarchie, Pfeile, Verbindungen). "
            + "Markdown-Liste erlaubt.\n"
            + "   - document_scan: transkribiere den vollständigen sichtbaren Text "
            + "wörtlich in Lese-Reihenfolge. Tabellen als Markdown-Tabellen. "
            + "KEIN Vorwort.\n"
            + "   - photo_general: prägnante Beschreibung (max 200 Wörter): was zu "
            + "sehen ist, erkennbarer Text, Kontext.\n\n"
            + "Antworte AUSSCHLIESSLICH mit folgendem JSON, keine Code-Fences, "
            + "kein Vorwort:\n"
            + "{\"sub_type\":\"<value>\",\"content\":\"<value>\"}";

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
        return call(imageBytes, mimeType, DESCRIBE_PROMPT, 600);
    }

    /**
     * OCR-style transcription: returns the verbatim text seen on the image.
     * Used by OcrService as a fallback when Tesseract output is too sparse.
     */
    public VisionResult transcribe(byte[] imageBytes, String mimeType) {
        return call(imageBytes, mimeType, TRANSCRIBE_PROMPT, 4000);
    }


    /**
     * Image-classification call: returns sub_type + sub-type-appropriate content.
     * Uses the same call() pipeline as describe()/transcribe(), then parses the
     * Haiku JSON response via {@link AnthropicVisionResponseParser}.
     */
    public ImageDescriptionResult describeImage(byte[] imageBytes, String mimeType) {
        VisionResult raw = call(imageBytes, mimeType, IMAGE_CLASSIFY_PROMPT, 4000);
        AnthropicVisionResponseParser.Parsed parsed =
                AnthropicVisionResponseParser.parse(raw.description());
        return new ImageDescriptionResult(
                parsed.subType(), parsed.content(),
                raw.inputTokens(), raw.outputTokens());
    }

    private VisionResult call(byte[] imageBytes, String mimeType, String prompt, int maxTokens) {
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
                "max_tokens", maxTokens,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "image",
                                        "source", Map.of(
                                                "type", "base64",
                                                "media_type", mimeType,
                                                "data", base64)),
                                Map.of("type", "text", "text", prompt)
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
