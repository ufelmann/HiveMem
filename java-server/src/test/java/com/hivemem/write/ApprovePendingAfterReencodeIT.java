package com.hivemem.write;

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
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T3 widened the re-encode's batch fetch to cover every cell status, not just {@code committed},
 * because a pending cell can be approved (flipped to {@code committed}) at any time and
 * {@code approvePending} only changes {@code status} — it never touches {@code embedding}. Before
 * T3, a pending cell left at the old vector dimension by a re-encode would still carry that
 * stale-dimension vector after approval, silently poisoning the (non-partial) HNSW index cast and
 * leaving the cell unsearchable at the new dimension. This IT drives a real dimension change over
 * a pending cell and then approves it, proving the vector is already correct by the time
 * {@code approvePending} runs — no separate re-encode step required.
 */
@Testcontainers
class ApprovePendingAfterReencodeIT {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    private DSLContext dsl;
    private EmbeddingStateRepository stateRepository;
    private EmbeddingMigrationService migrationService;
    private WriteToolRepository writeToolRepository;

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

        EmbeddingClient stubClient = new FixedEmbeddingClient(1024, "new-model");
        migrationService = EmbeddingMigrationServiceTestFactory.withStubBackup(stubClient, stateRepository, () -> { });
        writeToolRepository = new WriteToolRepository(dsl);
    }

    @Test
    void approvedPendingCellHasAUsableVectorImmediately() {
        UUID id = insertCell("pending", "pending content", dim384Vector());

        migrationService.run(null); // stub client reports dimension 1024
        writeToolRepository.approvePending(List.of(id), "committed");

        Integer dimension = dsl.fetchOne("SELECT vector_dims(embedding) AS d FROM cells WHERE id = ?", id)
                .get("d", Integer.class);
        assertThat(dimension)
                .as("approvePending only flips status — the vector must already be correct")
                .isEqualTo(1024);
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
