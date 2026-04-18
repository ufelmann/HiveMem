package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Order(4)
public class GetDrawerToolHandler implements ToolHandler {

    private final ReadToolService readToolService;

    public GetDrawerToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() {
        return "hivemem_get_drawer";
    }

    @Override
    public String description() {
        return "Single drawer by UUID with all L0-L3 layers.";
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

        return readToolService.getDrawer(principal, UUID.fromString(drawerId));
    }
}
