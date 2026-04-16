package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Order(10)
public class DrawerHistoryToolHandler implements ToolHandler {

    private final ReadToolService readToolService;

    public DrawerHistoryToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() {
        return "hivemem_drawer_history";
    }

    @Override
    public String description() {
        return "Trace revisions of a drawer.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String drawerId = requiredText(arguments, "drawer_id");
        return readToolService.drawerHistory(UUID.fromString(drawerId));
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
