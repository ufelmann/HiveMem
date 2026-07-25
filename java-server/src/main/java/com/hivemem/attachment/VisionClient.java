package com.hivemem.attachment;

import com.hivemem.llm.LlmCallCost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.Map;
import java.util.Set;

@Component
public class VisionClient {

    private static final Logger log = LoggerFactory.getLogger(VisionClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A vision call's text plus what Vistierie reports it cost, for the budget tracker. */
    public record VisionResult(String description, LlmCallCost cost) {}

    /** {@link #describeImage}'s parsed classification, carrying the same cost record. */
    public record ImageDescriptionResult(String subType, String content, LlmCallCost cost) {}

    public static class OversizeImageException extends RuntimeException {
        public OversizeImageException(String msg) { super(msg); }
    }

    /**
     * The provider answered with no usable text. This is a failure of the functional path only:
     * the call was made and billed, so the exception carries the call's cost and callers must
     * still book it. Without that, an image the model always answers blank keeps its pending
     * tag, the hourly backfill pays for it again every hour, {@code total_cost_usd} never moves
     * and the daily cap never fires — an unbounded spend loop.
     */
    public static class EmptyResponseException extends IllegalStateException {
        private final transient LlmCallCost cost;

        public EmptyResponseException(String msg, LlmCallCost cost) {
            super(msg);
            this.cost = cost;
        }

        /** What the (billed) call cost, for the caller to book. */
        public LlmCallCost cost() { return cost; }
    }

    private static final Set<String> SUPPORTED_MIME = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp");

    private static final Set<String> KNOWN_SUB_TYPES =
            Set.of("whiteboard_photo", "document_scan", "photo_general");
    private static final String DEFAULT_SUB_TYPE = "photo_general";

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

    private final RestClient http;
    private final String vistierieToken;
    private final long visionMaxInputBytes;
    private final String agentName;

    @Autowired
    public VisionClient(AttachmentProperties props) {
        this(buildRestClient(props), props.getVistierieToken(), props.getVisionMaxInputBytes(),
                props.getVisionAgentName());
    }

    /** Package-private constructor for tests. */
    VisionClient(RestClient http, String vistierieToken, long visionMaxInputBytes,
                 String agentName) {
        this.http = http;
        this.vistierieToken = vistierieToken;
        this.visionMaxInputBytes = visionMaxInputBytes;
        this.agentName = agentName;
    }

    private static RestClient buildRestClient(AttachmentProperties props) {
        int timeoutMs = props.getVisionTimeoutSeconds() * 1000;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(timeoutMs);
        rf.setReadTimeout(timeoutMs);
        return RestClient.builder()
                .baseUrl(props.getVistierieBaseUrl())
                .requestFactory(rf)
                .build();
    }

    public boolean isEnabled() {
        return vistierieToken != null && !vistierieToken.isBlank();
    }

    /**
     * Throws OversizeImageException when bytes exceed configured cap.
     * Throws IllegalArgumentException for unsupported MIME types.
     * Lets HttpClientErrorException.TooManyRequests bubble for 429 (caller backoff).
     */
    public VisionResult describe(byte[] imageBytes, String mimeType) {
        return call(imageBytes, mimeType, DESCRIBE_PROMPT, 600, "vision_describe");
    }

    /**
     * OCR-style transcription: returns the verbatim text seen on the image.
     * Used by OcrService as a fallback when Tesseract output is too sparse.
     */
    public VisionResult transcribe(byte[] imageBytes, String mimeType) {
        return call(imageBytes, mimeType, TRANSCRIBE_PROMPT, 4000, "vision_transcribe");
    }

    /**
     * Image-classification call: returns sub_type + sub-type-appropriate content.
     * The Vistierie response text is a JSON blob; this method parses it inline.
     */
    public ImageDescriptionResult describeImage(byte[] imageBytes, String mimeType) {
        VisionResult raw = call(imageBytes, mimeType, IMAGE_CLASSIFY_PROMPT, 4000, "vision_classify");
        String subType = DEFAULT_SUB_TYPE;
        String content = raw.description();
        // Parse JSON classification response (three fallback strategies)
        JsonNode node = tryParseJson(raw.description());
        if (node == null) {
            int first = raw.description().indexOf('{');
            int last = raw.description().lastIndexOf('}');
            if (first >= 0 && last > first) {
                node = tryParseJson(raw.description().substring(first, last + 1));
            }
        }
        if (node != null && node.isObject()) {
            String st = node.path("sub_type").asText("");
            subType = KNOWN_SUB_TYPES.contains(st) ? st : DEFAULT_SUB_TYPE;
            content = node.path("content").asText("");
        } else {
            log.warn("Vision classification response not parseable as JSON (len={})",
                    raw.description().length());
            subType = DEFAULT_SUB_TYPE;
            content = raw.description();
        }
        return new ImageDescriptionResult(subType, content, raw.cost());
    }

    private JsonNode tryParseJson(String text) {
        try {
            JsonNode n = MAPPER.readTree(text);
            return n.isObject() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    private VisionResult call(byte[] imageBytes, String mimeType, String prompt, int maxTokens,
                               String purpose) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("imageBytes empty");
        }
        if (imageBytes.length > visionMaxInputBytes) {
            throw new OversizeImageException("image " + imageBytes.length
                    + " bytes exceeds cap " + visionMaxInputBytes);
        }
        if (!SUPPORTED_MIME.contains(mimeType)) {
            throw new IllegalArgumentException("Unsupported image mime: " + mimeType);
        }

        // agent_name is @NotBlank on Vistierie's /llm/vision since 2026-05-17 (budget
        // enforcement on direct LLM calls) — omitting it is rejected with 400 before any
        // provider call is made.
        var body = Map.<String, Object>of(
                "agent_name", agentName,
                "purpose", purpose,
                "realm", "attachment",
                "image", Map.of(
                        "type", "base64",
                        "media_type", mimeType,
                        "data", Base64.getEncoder().encodeToString(imageBytes)),
                "prompt", prompt,
                "max_tokens", maxTokens
        );

        JsonNode resp = http.post()
                .uri("/llm/vision")
                .header("Authorization", "Bearer " + vistierieToken)
                .header("content-type", "application/json")
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        if (resp == null) throw new IllegalStateException("Vistierie returned null");
        String text = resp.path("text").asText();
        if (text == null || text.isBlank()) {
            // The call was already made and billed — hand the cost to the caller so it gets
            // booked even though there is nothing usable to return.
            throw new EmptyResponseException("Vistierie returned empty text", LlmCallCost.from(resp));
        }
        // Report what Vistierie actually did (its routed provider/model and every token kind),
        // not what HiveMem asked for: the two may differ.
        return new VisionResult(text.strip(), LlmCallCost.from(resp));
    }
}
