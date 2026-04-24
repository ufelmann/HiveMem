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
                .hasSize(31)
                .contains("search", "add_cell", "approve_pending",
                        "health", "reclassify_cell")
                .doesNotContain("hivemem_check_duplicate", "hivemem_check_contradiction");
    }

    @Test
    void writerPermissionSetContainsReadAndWriteToolsButNoAdminTools() {
        assertThat(toolPermissionService.allowedTools(AuthRole.WRITER))
                .hasSize(29)
                .contains("search", "add_cell", "revise_cell", "reclassify_cell")
                .doesNotContain("health", "approve_pending", "hivemem_check_duplicate",
                        "hivemem_check_contradiction");
    }
}
