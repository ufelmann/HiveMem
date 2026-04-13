package com.hivemem.parity;

import java.util.List;

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

    record ParitySuite(String suiteName, List<String> pythonSources) {

        ParitySuite(String suiteName, String... pythonSources) {
            this(suiteName, List.of(pythonSources));
        }
    }
}
