package com.hivemem.attachment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.Optional;

@Component
public class KrokiClient {

    private static final Logger log = LoggerFactory.getLogger(KrokiClient.class);

    /** MIME → Kroki format-path. Phase 1 ships 4 popular formats. */
    static final Map<String, String> MIME_TO_FORMAT = Map.of(
            "text/x-mermaid",     "mermaid",
            "text/x-plantuml",    "plantuml",
            "text/vnd.graphviz",  "graphviz",
            "text/x-d2",          "d2"
    );

    private final AttachmentProperties props;
    private final RestClient client;

    @Autowired
    public KrokiClient(AttachmentProperties props, RestClient.Builder builder) {
        this(props, builder, true);
    }

    KrokiClient(AttachmentProperties props, RestClient.Builder builder, boolean configureRequestFactory) {
        this.props = props;
        if (configureRequestFactory) {
            int timeoutMs = props.getKrokiTimeoutSeconds() * 1000;
            SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
            rf.setConnectTimeout(timeoutMs);
            rf.setReadTimeout(timeoutMs);
            builder = builder.requestFactory(rf);
        }
        String baseUrl = props.getKrokiUrl() == null || props.getKrokiUrl().isBlank()
                ? "http://localhost:8000" : props.getKrokiUrl();
        this.client = builder.baseUrl(baseUrl).build();
    }

    public boolean isEnabled() {
        return props.getKrokiUrl() != null && !props.getKrokiUrl().isBlank();
    }

    public boolean supports(String mimeType) {
        return mimeType != null && MIME_TO_FORMAT.containsKey(mimeType);
    }

    /** Returns rendered PNG bytes, or empty on 4xx/syntax error/unsupported. Throws on 5xx/timeout. */
    public Optional<byte[]> render(String mimeType, String diagramText) {
        String format = MIME_TO_FORMAT.get(mimeType);
        if (format == null) return Optional.empty();
        if (diagramText == null || diagramText.isBlank()) return Optional.empty();
        try {
            byte[] png = client.post()
                    .uri("/{format}/png", format)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(diagramText)
                    .retrieve()
                    .body(byte[].class);
            return png != null && png.length > 0 ? Optional.of(png) : Optional.empty();
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            if (code >= 400 && code < 500) {
                log.warn("Kroki {} rejected diagram (HTTP {}): {}", format, code, e.getMessage());
                return Optional.empty();
            }
            throw e; // 5xx bubbles up so caller can retry on next backfill
        }
    }
}
