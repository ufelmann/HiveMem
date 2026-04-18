package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Order(10)
public class HistoryToolHandler implements ToolHandler {

    private final ReadToolService readToolService;

    public HistoryToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() {
        return "hivemem_history";
    }

    @Override
    public String description() {
        return "Trace revisions of a drawer or fact by id.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String type = requiredText(arguments, "type");
        String id = requiredText(arguments, "id");
        UUID uuid = UUID.fromString(id);
        return switch (type) {
            case "drawer" -> readToolService.drawerHistory(uuid);
            case "fact" -> readToolService.factHistory(uuid);
            default -> throw new IllegalArgumentException("Invalid type, must be 'drawer' or 'fact'");
        };
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
