package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(8)
public class QuickFactsToolHandler implements ToolHandler {

    private final ReadToolService readToolService;

    public QuickFactsToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() {
        return "hivemem_quick_facts";
    }

    @Override
    public String description() {
        return "Context-aware facts about an entity.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String entity = requiredText(arguments, "entity");
        return readToolService.quickFacts(entity);
    }

    private static String requiredText(JsonNode arguments, String field) {
        if (arguments == null || !arguments.hasNonNull(field)) {
            throw new IllegalArgumentException("Missing " + field);
        }
        String value = arguments.get(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        return value;
    }
}
