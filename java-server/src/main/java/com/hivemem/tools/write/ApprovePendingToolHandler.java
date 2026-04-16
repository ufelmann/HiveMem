package com.hivemem.tools.write;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.write.WriteArgumentParser;
import com.hivemem.write.WriteToolService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@Order(22)
public class ApprovePendingToolHandler implements ToolHandler {

    private static final List<String> VALID_DECISIONS = List.of("committed", "rejected");

    private final WriteToolService writeToolService;

    public ApprovePendingToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
    }

    @Override
    public String name() {
        return "hivemem_approve_pending";
    }

    @Override
    public String description() {
        return "Approve or reject pending drawers, facts, and tunnels by ID.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        List<UUID> ids = WriteArgumentParser.requiredUuidList(arguments, "ids");
        String decision = requiredDecision(arguments);
        return writeToolService.approvePending(ids, decision);
    }

    private static String requiredDecision(JsonNode arguments) {
        String decision = WriteArgumentParser.requiredText(arguments, "decision");
        if (!VALID_DECISIONS.contains(decision)) {
            throw new IllegalArgumentException("Invalid decision '" + decision + "'. Must be 'committed' or 'rejected'.");
        }
        return decision;
    }
}
