package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(6)
public class ListHallsToolHandler implements ToolHandler {

    private final ReadToolService readToolService;

    public ListHallsToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() {
        return "hivemem_list_halls";
    }

    @Override
    public String description() {
        return "Halls within a wing.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        if (arguments == null || !arguments.hasNonNull("wing")) {
            throw new IllegalArgumentException("Missing wing");
        }

        String wing = arguments.get("wing").asText();
        if (wing.isBlank()) {
            throw new IllegalArgumentException("Missing wing");
        }

        return readToolService.listHalls(wing);
    }
}
