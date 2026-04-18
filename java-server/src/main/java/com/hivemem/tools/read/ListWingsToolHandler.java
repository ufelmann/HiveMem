package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
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
        return "Wings with counts, or halls of a specific wing when 'wing' is provided.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String wing = null;
        if (arguments != null && arguments.hasNonNull("wing")) {
            String value = arguments.get("wing").asText();
            if (!value.isBlank()) {
                wing = value;
            }
        }
        if (wing == null) {
            return readToolService.listWings();
        }
        return readToolService.listHalls(wing);
    }
}
