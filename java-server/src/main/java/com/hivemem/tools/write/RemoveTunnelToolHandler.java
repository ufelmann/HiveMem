package com.hivemem.tools.write;

import com.fasterxml.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.write.WriteArgumentParser;
import com.hivemem.write.WriteToolService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Order(31)
public class RemoveTunnelToolHandler implements ToolHandler {

    private final WriteToolService writeToolService;

    public RemoveTunnelToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
    }

    @Override
    public String name() {
        return "hivemem_remove_tunnel";
    }

    @Override
    public String description() {
        return "Soft-delete a tunnel by setting valid_until.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        UUID tunnelId = WriteArgumentParser.requiredUuid(arguments, "tunnel_id");
        return writeToolService.removeTunnel(tunnelId);
    }
}
