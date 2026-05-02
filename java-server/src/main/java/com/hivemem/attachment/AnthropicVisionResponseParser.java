package com.hivemem.attachment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

/**
 * Parses Haiku image-classification responses into {@link Parsed} with
 * three fallback strategies. Pure utility, package-private for VisionClient.
 */
final class AnthropicVisionResponseParser {

    private static final Logger log = LoggerFactory.getLogger(AnthropicVisionResponseParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> KNOWN_SUB_TYPES =
            Set.of("whiteboard_photo", "document_scan", "photo_general");
    private static final String DEFAULT_SUB_TYPE = "photo_general";

    public record Parsed(String subType, String content) {}

    private AnthropicVisionResponseParser() {}

    public static Parsed parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return new Parsed(DEFAULT_SUB_TYPE, "");
        }
        // Strategy 1: parse the raw text as-is.
        Parsed p = tryParse(rawText);
        if (p != null) return normalize(p);

        // Strategy 2: extract first { ... last } and parse that.
        int first = rawText.indexOf('{');
        int last = rawText.lastIndexOf('}');
        if (first >= 0 && last > first) {
            String slice = rawText.substring(first, last + 1);
            p = tryParse(slice);
            if (p != null) return normalize(p);
        }

        // Strategy 3: full-text fallback.
        log.warn("Vision response not parseable as JSON, using raw fallback (len={})",
                rawText.length());
        return new Parsed(DEFAULT_SUB_TYPE, rawText);
    }

    private static Parsed tryParse(String text) {
        try {
            JsonNode node = MAPPER.readTree(text);
            if (!node.isObject()) return null;
            String subType = node.path("sub_type").asText("");
            String content = node.path("content").asText("");
            return new Parsed(subType, content);
        } catch (Exception e) {
            return null;
        }
    }

    private static Parsed normalize(Parsed p) {
        String subType = KNOWN_SUB_TYPES.contains(p.subType()) ? p.subType() : DEFAULT_SUB_TYPE;
        String content = p.content() == null ? "" : p.content();
        return new Parsed(subType, content);
    }
}
