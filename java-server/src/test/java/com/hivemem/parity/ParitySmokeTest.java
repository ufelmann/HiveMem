package com.hivemem.parity;

import com.hivemem.auth.AuthRole;
import com.hivemem.auth.ToolPermissionService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for the role/tool permission surface. The name "parity" is historical —
 * this suite originally guarded parity with a (now-removed) Python implementation; today
 * it simply verifies that the per-role allow-lists remain in sync with the tool registry.
 */
class ParitySmokeTest {

    private final ToolPermissionService toolPermissionService = new ToolPermissionService();

    @Test
    void adminPermissionSetContainsFullExpectedToolCount() {
        assertThat(toolPermissionService.allowedTools(AuthRole.ADMIN))
                .hasSize(38)
                .contains("hivemem_search", "hivemem_add_drawer", "hivemem_refresh_popularity",
                        "hivemem_mine_file", "hivemem_mine_directory");
    }

    @Test
    void writerPermissionSetContainsReadAndWriteToolsButNoAdminTools() {
        assertThat(toolPermissionService.allowedTools(AuthRole.WRITER))
                .hasSize(34)
                .contains("hivemem_search", "hivemem_add_drawer", "hivemem_revise_drawer",
                        "hivemem_mine_file", "hivemem_mine_directory")
                .doesNotContain("hivemem_health", "hivemem_log_access", "hivemem_refresh_popularity",
                        "hivemem_approve_pending");
    }
}
