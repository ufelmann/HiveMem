package com.hivemem.summarize;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Closes a review gap left by the embed-cap raise (500 -> 8000 chars on the Ollama backend):
 * the spec's testing section promised this class -- "a 3000-char cell appears in
 * findCellsNeedingSummary and its facts are extracted" -- but it was never written. Only
 * {@link NeedsSummaryDeciderTest} covered the threshold decision, at unit level, without ever
 * touching {@link SummarizerRepository#findCellsNeedingSummary}.
 *
 * <p>That gap matters because raising the embed cap did not raise the summary threshold
 * ({@link NeedsSummaryDecider#DEFAULT_THRESHOLD_CHARS} stays 500): a cell between 500 and 8000
 * characters is now short enough to be embedded from its raw content on Ollama, but the pipeline
 * must still tag it {@code needs_summary} and enrich it (summary, key points, facts) -- otherwise
 * raising the cap would have silently stopped enrichment for that whole band of cells (measured at
 * 1101 cells in production). This test pins the repository-level guarantee: a committed 3000-char
 * cell with no summary, once tagged the way the real pipeline tags it, is returned by
 * {@code findCellsNeedingSummary}.
 *
 * <p>Live LLM fact extraction is deliberately NOT exercised here -- it would require a real model
 * call and is out of scope for a database-level regression guard. The repository-query half is
 * what this class pins; the extraction half is assumed to run once a cell is claimed and handed to
 * the (separately tested) extraction pipeline.
 *
 * <p>Modeled on {@link com.hivemem.embedding.EmbeddingReencodeKeysetPaginationIT}: a bare
 * Testcontainers Postgres + Flyway migration + jOOQ DSL, no Spring context.
 */
@Testcontainers
class SummarizerTriggerThresholdIT {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    private DSLContext dsl;
    private SummarizerRepository repo;

    @BeforeEach
    void setUp() {
        org.flywaydb.core.Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .locations("classpath:db/migration").load().migrate();
        DataSource ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        dsl.execute("DELETE FROM facts");
        dsl.execute("DELETE FROM cells");

        repo = new SummarizerRepository(dsl);
    }

    @Test
    void cellBetweenOnnxAndOllamaCapsIsSurfacedForEnrichment() {
        // 3000 chars: below the Ollama embed cap (8000, so it is embedded from raw content on
        // that backend) but far above the summary-need threshold (500) and the old ONNX embed
        // cap (500) -- exactly the band the review flagged as at risk of silently losing
        // enrichment once the embed cap moved independently of the summary threshold.
        String content = "x".repeat(3000);
        assertThat(NeedsSummaryDecider.needsSummary(content, null)).isTrue();

        UUID id = insertCommittedCell(content, null);
        repo.tagNeedsSummary(id);

        assertThat(repo.findCellsNeedingSummary(10)).contains(id);
    }

    @Test
    void cellWithExistingSummaryIsNotSurfaced() {
        // Control: a cell that already has a summary must not need re-enrichment, even at the
        // same content length -- proves the assertion above is discriminating on tag/summary
        // state, not just returning every committed cell.
        String content = "x".repeat(3000);
        assertThat(NeedsSummaryDecider.needsSummary(content, "already summarized")).isFalse();

        UUID id = insertCommittedCell(content, "already summarized");
        // Deliberately not tagged needs_summary, mirroring what the real pipeline would do.

        assertThat(repo.findCellsNeedingSummary(10)).doesNotContain(id);
    }

    private UUID insertCommittedCell(String content, String summary) {
        UUID id = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO cells (id, content, summary, realm, status, created_by, valid_from)
                VALUES (?, ?, ?, 'test', 'committed', 'test', now())
                """, id, content, summary);
        return id;
    }
}
