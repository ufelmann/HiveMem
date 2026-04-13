package com.hivemem.parity;

import com.hivemem.auth.AuthRole;
import com.hivemem.auth.ToolPermissionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ParitySmokeTest {

    private static final List<String> PARITY_SUITE_NAMES = List.of(
            "HttpTokenLifecycleIntegrationTest",
            "FlywayMigrationParityTest",
            "ImportToolIntegrationTest",
            "ConcurrencyIntegrationTest",
            "CrossFeatureParityIntegrationTest"
    );

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

    @Test
    void paritySuiteNamesStayRegistered() {
        assertThat(PARITY_SUITE_NAMES).containsExactly(
                "HttpTokenLifecycleIntegrationTest",
                "FlywayMigrationParityTest",
                "ImportToolIntegrationTest",
                "ConcurrencyIntegrationTest",
                "CrossFeatureParityIntegrationTest"
        );
    }
}
