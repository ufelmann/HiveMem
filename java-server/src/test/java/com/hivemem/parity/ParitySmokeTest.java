package com.hivemem.parity;

import com.hivemem.auth.AuthRole;
import com.hivemem.auth.ToolPermissionService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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
                .hasSize(32)
                .contains("hivemem_search", "hivemem_add_drawer", "hivemem_revise_drawer")
                .doesNotContain("hivemem_health", "hivemem_log_access", "hivemem_refresh_popularity",
                        "hivemem_mine_file", "hivemem_mine_directory");
    }

    @Test
    void paritySuiteMatrixMatchesCanonicalMappings() {
        assertThat(ParityMatrix.SUITES)
                .extracting(ParityMatrix.ParitySuite::suiteName, ParityMatrix.ParitySuite::pythonSources)
                .containsExactly(
                        tuple("HttpTokenLifecycleIntegrationTest", java.util.List.of("tests/test_http_integration.py")),
                        tuple("FlywayMigrationParityTest", java.util.List.of("tests/test_migrations.py", "tests/test_edges_migration.py")),
                        tuple("ImportToolIntegrationTest", java.util.List.of("tests/test_import.py")),
                        tuple("ConcurrencyIntegrationTest", java.util.List.of("tests/test_concurrency.py")),
                        tuple("CrossFeatureParityIntegrationTest", java.util.List.of("tests/test_integration.py"))
                );
    }

    @Test
    void readmeParitySectionMatchesCanonicalMarkdown() throws IOException {
        String readme = Files.readString(Path.of("..", "README.md"), StandardCharsets.UTF_8);
        String section = extractReadmeParitySection(readme);

        assertThat(section).isEqualTo(ParityMatrix.renderReadmeSection());
    }

    private static String extractReadmeParitySection(String readme) {
        String heading = "## Java Test Parity";
        String nextHeading = "\n## ";
        int start = readme.indexOf(heading);
        int end = readme.indexOf(nextHeading, start + heading.length());
        if (start < 0) {
            throw new AssertionError("README is missing the Java Test Parity section");
        }
        if (end < 0) {
            throw new AssertionError("README is missing the section that follows Java Test Parity");
        }
        return readme.substring(start, end).stripTrailing();
    }
}
