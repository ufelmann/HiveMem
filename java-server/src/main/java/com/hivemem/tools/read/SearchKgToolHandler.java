package com.hivemem.tools.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(3)
public class SearchKgToolHandler implements ToolHandler {
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 100;

    private final ReadToolService readToolService;

    public SearchKgToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() {
        return "hivemem_search_kg";
    }

    @Override
    public String description() {
        return "ILIKE search on active facts.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String subject = textValue(arguments, "subject");
        String predicate = textValue(arguments, "predicate");
        String object_ = textValue(arguments, "object_");
        int limit = intValue(arguments, "limit");
        return readToolService.searchKg(subject, predicate, object_, limit);
    }

    private static String textValue(JsonNode arguments, String field) {
        if (arguments == null || !arguments.hasNonNull(field)) {
            return null;
        }
        String value = arguments.get(field).asText();
        return value.isBlank() ? null : value;
    }

    private static int intValue(JsonNode arguments, String field) {
        if (arguments == null || !arguments.hasNonNull(field)) {
            return DEFAULT_LIMIT;
        }
        JsonNode node = arguments.get(field);
        if (!node.canConvertToInt()) {
            throw new IllegalArgumentException("Invalid limit");
        }
        int value = node.intValue();
        if (value <= 0 || value > MAX_LIMIT) {
            throw new IllegalArgumentException("Invalid limit");
        }
        return value;
    }
}
