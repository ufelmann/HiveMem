package com.hivemem.tools.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Order(11)
public class FactHistoryToolHandler implements ToolHandler {

    private final ReadToolService readToolService;

    public FactHistoryToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() {
        return "hivemem_fact_history";
    }

    @Override
    public String description() {
        return "Trace revisions of a fact.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String factId = requiredText(arguments, "fact_id");
        return readToolService.factHistory(UUID.fromString(factId));
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
