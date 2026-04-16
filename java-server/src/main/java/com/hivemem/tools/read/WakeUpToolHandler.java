package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(17)
public class WakeUpToolHandler implements ToolHandler {

    private final ReadToolService readToolService;

    public WakeUpToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() {
        return "hivemem_wake_up";
    }

    @Override
    public String description() {
        return "Load identity context at session start.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        return readToolService.wakeUp();
    }
}
