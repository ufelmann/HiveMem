package com.hivemem.parity;

import com.hivemem.auth.AuthRole;
import com.hivemem.auth.ToolPermissionService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ParitySmokeTest {

    private final ToolPermissionService toolPermissionService = new ToolPermissionService();

    @Test
    void adminPermissionSetContainsFullExpectedToolCount() {
        assertThat(toolPermissionService.allowedTools(AuthRole.ADMIN))
                .hasSize(36)
                .contains("hivemem_search", "hivemem_add_drawer", "hivemem_refresh_popularity");
    }

    @Test
    void writerPermissionSetContainsReadAndWriteToolsButNoAdminTools() {
        assertThat(toolPermissionService.allowedTools(AuthRole.WRITER))
                .hasSize(32)
                .contains("hivemem_search", "hivemem_add_drawer", "hivemem_revise_drawer")
                .doesNotContain("hivemem_health", "hivemem_log_access", "hivemem_refresh_popularity");
    }
}
