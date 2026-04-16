package com.hivemem.tools.admin;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.write.AdminToolService;
import com.hivemem.write.WriteArgumentParser;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Order(41)
public class LogAccessToolHandler implements ToolHandler {

    private final AdminToolService adminToolService;

    public LogAccessToolHandler(AdminToolService adminToolService) {
        this.adminToolService = adminToolService;
    }

    @Override
    public String name() {
        return "hivemem_log_access";
    }

    @Override
    public String description() {
        return "Log an access event for popularity tracking.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        UUID drawerId = WriteArgumentParser.optionalUuid(arguments, "drawer_id");
        UUID factId = WriteArgumentParser.optionalUuid(arguments, "fact_id");
        if (drawerId == null && factId == null) {
            throw new IllegalArgumentException("Missing drawer_id");
        }
        return adminToolService.logAccess(drawerId, factId, principal.name());
    }
}
