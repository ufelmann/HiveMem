package com.hivemem.summarize;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class SummarizeBackfillStartupRunnerIT {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    private DSLContext dsl;

    @BeforeEach
    void setUp() {
        org.flywaydb.core.Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .locations("classpath:db/migration").load().migrate();
        DataSource ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        dsl.execute("DELETE FROM cells");
    }

    @Test
    void tagsLongCells_skipsShortAndAlreadyTagged_andSummarized() throws Exception {
        // Seed: 4 cells
        // 1. long, no summary, not tagged → SHOULD be tagged
        // 2. short, no summary → should NOT be tagged
        // 3. long, with summary → should NOT be tagged
        // 4. long, no summary, already tagged → should remain tagged (no duplicate)
        UUID id1 = UUID.randomUUID(), id2 = UUID.randomUUID(), id3 = UUID.randomUUID(), id4 = UUID.randomUUID();
        try (Connection c = DriverManager.getConnection(
                DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
             Statement st = c.createStatement()) {
            String longText = "a".repeat(800);
            st.execute("INSERT INTO cells (id, content, realm, signal, status, tags, created_at, valid_from) VALUES "
                    + "('" + id1 + "', '" + longText + "', 'r', 'facts', 'committed', ARRAY[]::text[], now(), now()),"
                    + "('" + id2 + "', 'short', 'r', 'facts', 'committed', ARRAY[]::text[], now(), now()),"
                    + "('" + id3 + "', '" + longText + "', 'r', 'facts', 'committed', ARRAY[]::text[], now(), now()),"
                    + "('" + id4 + "', '" + longText + "', 'r', 'facts', 'committed', ARRAY['needs_summary']::text[], now(), now())");
            st.execute("UPDATE cells SET summary = 'has summary' WHERE id = '" + id3 + "'");
        }

        SummarizerProperties props = new SummarizerProperties();
        props.setEnabled(true);
        props.setSummaryThresholdChars(500);
        SummarizeBackfillStartupRunner runner = new SummarizeBackfillStartupRunner(dsl, props);
        runner.run(new DefaultApplicationArguments());

        try (Connection c = DriverManager.getConnection(
                DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
             Statement st = c.createStatement()) {
            assertTrue(hasTag(st, id1), "long cell #1 should be tagged");
            assertFalse(hasTag(st, id2), "short cell #2 should NOT be tagged");
            assertFalse(hasTag(st, id3), "summarized cell #3 should NOT be tagged");
            assertTrue(hasTag(st, id4), "already-tagged cell #4 should still be tagged");
            // Verify cell #4 has only one occurrence (no duplicate)
            try (ResultSet rs = st.executeQuery(
                    "SELECT array_length(tags, 1) FROM cells WHERE id = '" + id4 + "'")) {
                rs.next();
                assertEquals(1, rs.getInt(1), "cell #4 should still have exactly 1 tag");
            }
        }
    }

    private static boolean hasTag(Statement st, UUID id) throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT 'needs_summary' = ANY(tags) FROM cells WHERE id = '" + id + "'")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }
}
