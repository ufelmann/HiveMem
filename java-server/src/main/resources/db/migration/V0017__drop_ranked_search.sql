-- V0017: ranked_search is now managed by EmbeddingMigrationService so its
-- vector(N) cast can be kept in sync with the active embedding dimension.
-- Drop the Flyway-owned definition; the service recreates it on every startup.

DROP FUNCTION IF EXISTS ranked_search(vector, TEXT, TEXT, TEXT, TEXT, INTEGER, REAL, REAL, REAL, REAL, REAL);
