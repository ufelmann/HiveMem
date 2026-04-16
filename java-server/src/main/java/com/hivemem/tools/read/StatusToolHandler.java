package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Order(1)
public class StatusToolHandler implements ToolHandler {

    private final ReadToolService readToolService;

    public StatusToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() {
        return "hivemem_status";
    }

    @Override
    public String description() {
        return "Counts of drawers, facts, tunnels, wings list, and last activity.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        return readToolService.status();
    }
}
