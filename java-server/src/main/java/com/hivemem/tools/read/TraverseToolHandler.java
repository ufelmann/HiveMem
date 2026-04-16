package com.hivemem.tools.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Order(7)
public class TraverseToolHandler implements ToolHandler {
    private static final int DEFAULT_MAX_DEPTH = 2;
    private static final int MAX_MAX_DEPTH = 100;

    private final ReadToolService readToolService;

    public TraverseToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() {
        return "hivemem_traverse";
    }

    @Override
    public String description() {
        return "Bidirectional drawer-to-drawer graph traversal.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        if (arguments == null || !arguments.hasNonNull("drawer_id")) {
            throw new IllegalArgumentException("Missing drawer_id");
        }

        String drawerId = arguments.get("drawer_id").asText();
        if (drawerId.isBlank()) {
            throw new IllegalArgumentException("Missing drawer_id");
        }

        String relationFilter = textValue(arguments, "relation_filter");
        int maxDepth = intValue(arguments, "max_depth");
        return readToolService.traverse(UUID.fromString(drawerId), maxDepth, relationFilter);
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
            return DEFAULT_MAX_DEPTH;
        }
        JsonNode node = arguments.get(field);
        if (!node.canConvertToInt()) {
            throw new IllegalArgumentException("Invalid max_depth");
        }
        int value = node.intValue();
        if (value <= 0 || value > MAX_MAX_DEPTH) {
            throw new IllegalArgumentException("Invalid max_depth");
        }
        return value;
    }
}
