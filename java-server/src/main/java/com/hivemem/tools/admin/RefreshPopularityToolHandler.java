package com.hivemem.tools.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.write.AdminToolService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(42)
public class RefreshPopularityToolHandler implements ToolHandler {

    private final AdminToolService adminToolService;

    public RefreshPopularityToolHandler(AdminToolService adminToolService) {
        this.adminToolService = adminToolService;
    }

    @Override
    public String name() {
        return "hivemem_refresh_popularity";
    }

    @Override
    public String description() {
        return "Refresh the drawer popularity materialized view.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        return adminToolService.refreshPopularity();
    }
}
