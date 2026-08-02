package com.hivemem.embedding;

/**
 * Exposes {@link EmbeddingMigrationService}'s package-private, backup-runner-injecting
 * constructor to integration tests that live outside {@code com.hivemem.embedding} (e.g. the
 * cross-package IT suite pinning the re-encode invariants), without loosening the constructor's
 * visibility for production callers.
 */
public final class EmbeddingMigrationServiceTestFactory {

    private EmbeddingMigrationServiceTestFactory() {
    }

    /** Builds a service with a no-startup-retry-delay stub client and a stub backup runner, so
     *  tests can drive {@link EmbeddingMigrationService#run} without executing the real
     *  {@code hivemem-backup} binary. */
    public static EmbeddingMigrationService withStubBackup(
            EmbeddingClient embeddingClient, EmbeddingStateRepository stateRepository, Runnable backupRunner) {
        return new EmbeddingMigrationService(embeddingClient, stateRepository, 1, 0L, backupRunner);
    }
}
