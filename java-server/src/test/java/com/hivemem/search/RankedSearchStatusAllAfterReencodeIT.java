package com.hivemem.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.EmbeddingInfo;
import com.hivemem.embedding.EmbeddingMigrationService;
import com.hivemem.embedding.EmbeddingMigrationServiceTestFactory;
import com.hivemem.embedding.EmbeddingStateRepository;
import com.hivemem.embedding.FixedEmbeddingClient;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T3 widened {@code fetchCellBatch} to cover every cell status, not just {@code committed},
 * because the HNSW index rebuilt at the end of a re-encode is non-partial (covers the whole
 * table). Before T3, a pending row left at the old dimension would break the
 * {@code CREATE INDEX ... ((embedding::vector(newDim)))} cast, and even if the index build had
 * somehow survived, {@code ranked_search(..., p_status='all', ...)} would compare a mixed-
 * dimension column against a fixed-dimension query vector and error with "expected N dimensions,
 * not M". This IT drives a real dimension change across mixed-status cells and then exercises
 * {@code ranked_search} with {@code status='all'}, so a regression that reintroduces the
 * committed-only filter fails here even though it is invisible to unit tests.
 */
@Testcontainers
class RankedSearchStatusAllAfterReencodeIT {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    private DSLContext dsl;
    private EmbeddingStateRepository stateRepository;
    private EmbeddingMigrationService migrationService;
    private FixedEmbeddingClient stubClient;

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
        dsl.execute("DROP INDEX IF EXISTS idx_cells_embedding");
        dsl.execute("DROP INDEX IF EXISTS idx_facts_embedding");

        stateRepository = new EmbeddingStateRepository(dsl, ds);
        stateRepository.saveInfo(new EmbeddingInfo("old-model", 384));

        stubClient = new FixedEmbeddingClient(1024, "new-model");
        migrationService = EmbeddingMigrationServiceTestFactory.withStubBackup(stubClient, stateRepository, () -> { });
    }

    @Test
    void statusAllSearchWorksAfterAReencode() {
        seedMixedStatusCells();

        migrationService.run(null); // stub client reports dimension 1024

        Float[] queryVector = stubClient.encodeQuery("semantic content").toArray(Float[]::new);
        List<Record> rows = dsl.fetch(
                "SELECT * FROM ranked_search(?::vector, ?, NULL, NULL, NULL, 10, "
                        + "0.30, 0.15, 0.15, 0.15, 0.15, 0.10, NULL, 'all')",
                queryVector, "semantic content");

        assertThat(rows).as("status='all' must not error on a mixed-dimension table").isNotEmpty();
        assertThat(rows.stream().map(r -> r.get("content", String.class)))
                .as("the pending cell must be reachable via status='all'")
                .contains("semantic content two");
    }

    private void seedMixedStatusCells() {
        insertCell("committed", "semantic content one", dim384Vector());
        insertCell("pending", "semantic content two", dim384Vector());
        insertCell("rejected", "semantic content three", dim384Vector());
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
