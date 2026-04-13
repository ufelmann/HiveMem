package com.hivemem.parity;

import java.util.List;
import java.util.StringJoiner;

final class ParityMatrix {

    static final List<ParitySuite> SUITES = List.of(
            new ParitySuite("HttpTokenLifecycleIntegrationTest", "tests/test_http_integration.py"),
            new ParitySuite("FlywayMigrationParityTest", "tests/test_migrations.py", "tests/test_edges_migration.py"),
            new ParitySuite("ImportToolIntegrationTest", "tests/test_import.py"),
            new ParitySuite("ConcurrencyIntegrationTest", "tests/test_concurrency.py"),
            new ParitySuite("CrossFeatureParityIntegrationTest", "tests/test_integration.py")
    );

    private ParityMatrix() {
    }

    static List<String> suiteNames() {
        return SUITES.stream().map(ParitySuite::suiteName).toList();
    }

    static String renderReadmeSection() {
        StringJoiner joiner = new StringJoiner("\n");
        joiner.add("## Java Test Parity");
        joiner.add("");
        joiner.add("The canonical parity matrix lives in `java-server/src/test/java/com/hivemem/parity/ParityMatrix.java`. The README mirrors that test-side source of truth so the mapping stays easy to scan:");
        joiner.add("");
        for (ParitySuite suite : SUITES) {
            joiner.add("- `" + suite.suiteName() + "` replaces " + suite.description());
        }
        return joiner.toString();
    }

    record ParitySuite(String suiteName, List<String> pythonSources) {

        ParitySuite(String suiteName, String... pythonSources) {
            this(suiteName, List.of(pythonSources));
        }

        String description() {
            return switch (pythonSources.size()) {
                case 1 -> "the cases from `" + pythonSources.getFirst() + "`";
                default -> "cases from `" + pythonSources.getFirst() + "` and `" + pythonSources.get(1) + "`";
            };
        }
    }
}
