package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
@Order(4)
public class GetCellToolHandler implements ToolHandler {

    private final ReadToolService readToolService;

    public GetCellToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() {
        return "hivemem_get_cell";
    }

    @Override
    public String description() {
        return "Single cell by UUID with all L0-L3 layers.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredUuid("cell_id", "UUID of the cell to retrieve")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        if (arguments == null || !arguments.hasNonNull("cell_id")) {
            throw new IllegalArgumentException("Missing cell_id");
        }

        String cellId = arguments.get("cell_id").asText();
        if (cellId.isBlank()) {
            throw new IllegalArgumentException("Missing cell_id");
        }

        return readToolService.getCell(principal, UUID.fromString(cellId));
    }
}
