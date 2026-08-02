package com.hivemem.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.UUID;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Reproduces two production-only defects, fixed in T1 and T3, that unit tests cannot see because
 * they only exist in a deployed database:
 *
 * <ul>
 *   <li>T1: production carries {@code idx_drawers_embedding}, a stale HNSW index left over from
 *       the drawer->cell rename that no Flyway migration knows about. Before T1,
 *       {@code dropEmbeddingIndex()} dropped only the single hardcoded {@code idx_cells_embedding}
 *       name, so the re-encode's first UPDATE to a new dimension aborted against the stale index
 *       — after the known index had already been dropped.</li>
 *   <li>T3: the HNSW index rebuilt at the end of a re-encode is non-partial (covers the whole
 *       table, all statuses), so a pending or rejected row left at the old dimension breaks the
 *       {@code CREATE INDEX ... ((embedding::vector(newDim)))} cast just as surely as a committed
 *       one. Before T3, {@code fetchCellBatch} filtered {@code status = 'committed'}, silently
 *       skipping non-committed rows.</li>
 * </ul>
 *
 * This test recreates both shapes in Testcontainers — a manually created stale index under the
 * old drawer name, plus committed/pending/rejected rows — and drives a real dimension change
 * through {@link EmbeddingMigrationService#run}, so a future refactor that reintroduces either
 * defect fails this test even though every unit test around it stays green.
 */
@Testcontainers
class EmbeddingReencodeDimensionChangeIT {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    private DSLContext dsl;
    private EmbeddingStateRepository stateRepository;
    private EmbeddingMigrationService migrationService;

    @BeforeEach
    void setUp() {
        org.flywaydb.core.Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .locations("classpath:db/migration").load().migrate();
        DataSource ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        dsl.execute("DELETE FROM facts");
        dsl.execute("DELETE FROM cells");
        dsl.execute("DELETE FROM identity");
        // V0053 already removed the stale index from real databases; drop whatever the
        // migrations left behind so this test recreates the production shape from scratch.
        dsl.execute("DROP INDEX IF EXISTS idx_cells_embedding");
        dsl.execute("DROP INDEX IF EXISTS idx_facts_embedding");

        stateRepository = new EmbeddingStateRepository(dsl, ds);
        // Stored identity: an old model at the old dimension, so run() detects a mismatch
        // against the stub client (below) and takes the reencode path.
        stateRepository.saveInfo(new EmbeddingInfo("old-model", 384));

        // Stub client reports dimension 1024 under a new model name; startup retries are
        // pointless in-process, and the no-op backup runner keeps the test from exec'ing the
        // real hivemem-backup binary.
        EmbeddingClient stubClient = new FixedEmbeddingClient(1024, "new-model");
        migrationService = new EmbeddingMigrationService(stubClient, stateRepository, 1, 0L, () -> { });
    }

    @Test
    void migratesDespiteAStaleIndexAndNonCommittedRows() {
        dsl.execute("CREATE INDEX idx_drawers_embedding ON cells USING hnsw "
                + "((embedding::vector(384)) vector_cosine_ops)");
        insertCell("committed", "content a", dim384Vector());
        insertCell("pending", "content b", dim384Vector());
        insertCell("rejected", "content c", dim384Vector());

        migrationService.run(null); // stub client reports dimension 1024

        assertEquals(0, dsl.fetchOne(
                        "SELECT count(*)::int FROM cells WHERE embedding IS NOT NULL "
                                + "AND vector_dims(embedding) <> 1024")
                        .into(Integer.class),
                "no row may keep an old-dimension vector");
        assertEquals(1, countVectorIndexesOn("cells"), "exactly one index, at the new dimension");
        assertFalse(indexExists("idx_drawers_embedding"), "the stale index is gone for good");
    }

    private int countVectorIndexesOn(String table) {
        return dsl.fetchOne(
                        "SELECT count(*)::int FROM pg_index i "
                                + "JOIN pg_class c ON c.oid = i.indexrelid "
                                + "JOIN pg_am a ON a.oid = c.relam "
                                + "WHERE a.amname IN ('hnsw','ivfflat') AND i.indrelid = ?::regclass",
                        table)
                .into(Integer.class);
    }

    private boolean indexExists(String name) {
        Integer count = dsl.fetchOne(
                        "SELECT count(*)::int FROM pg_indexes WHERE indexname = ?", name)
                .into(Integer.class);
        return count != null && count > 0;
    }

    private UUID insertCell(String status, String content, Float[] embedding) {
        UUID id = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO cells (id, content, embedding, realm, signal, topic, status, created_by, valid_from)
                VALUES (?, ?, ?::vector, 'eng', 'facts', 'infra', ?, 'test', now())
                """, id, content, embedding, status);
        return id;
    }

    private static Float[] dim384Vector() {
        Float[] v = new Float[384];
        java.util.Arrays.fill(v, 0.1f);
        return v;
    }
}
