package com.hivemem.tools.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(5)
public class ListWingsToolHandler implements ToolHandler {

    private final ReadToolService readToolService;

    public ListWingsToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() {
        return "hivemem_list_wings";
    }

    @Override
    public String description() {
        return "Wings with hall and drawer counts.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        return readToolService.listWings();
    }
}
