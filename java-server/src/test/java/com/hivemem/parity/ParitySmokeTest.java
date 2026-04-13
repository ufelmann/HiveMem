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

    @Test
    void paritySuiteNamesStayRegistered() {
        assertThat(ParityMatrix.suiteNames())
                .hasSize(5)
                .contains("HttpTokenLifecycleIntegrationTest", "CrossFeatureParityIntegrationTest");
    }

    @Test
    void paritySuitesCarryPythonSourceMappings() {
        assertThat(ParityMatrix.SUITES)
                .hasSize(5)
                .allSatisfy(suite -> assertThat(suite.pythonSources()).isNotEmpty());
        assertThat(ParityMatrix.SUITES.stream()
                .flatMap(suite -> suite.pythonSources().stream())
                .toList())
                .contains(
                        "tests/test_http_integration.py",
                        "tests/test_edges_migration.py",
                        "tests/test_import.py",
                        "tests/test_concurrency.py",
                        "tests/test_integration.py");
    }
}
