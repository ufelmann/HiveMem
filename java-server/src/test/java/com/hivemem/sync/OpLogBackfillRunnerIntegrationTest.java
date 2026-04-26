package com.hivemem.sync;

import tools.jackson.databind.ObjectMapper;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(OpLogBackfillRunnerIntegrationTest.TestConfig.class)
class OpLogBackfillRunnerIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean @Primary
        EmbeddingClient testEmbeddingClient() { return new FixedEmbeddingClient(); }

        @Bean
        ObjectMapper objectMapper() { return new ObjectMapper(); }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null
                            ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig())
                            .withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired DSLContext dsl;
    @Autowired OpLogBackfillRunner runner;

    @Test
    void backfillEmitsOpsForExistingCellsAndIsIdempotent() {
        // Seed a cell directly via SQL (bypassing the service so no op-log entry yet).
        dsl.execute("DELETE FROM ops_log");
        UUID cellId = UUID.randomUUID();
        dsl.execute(
                "INSERT INTO cells (id, content, realm, signal, topic, created_by, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                cellId, "seed-content", "engineering", "facts", "t", "admin", "committed");

        long before = dsl.fetchOne("SELECT count(*) AS c FROM ops_log").get("c", Long.class);
        assertThat(before).isZero();

        runner.runBackfill();

        long afterFirst = dsl.fetchOne("SELECT count(*) AS c FROM ops_log").get("c", Long.class);
        assertThat(afterFirst).isGreaterThanOrEqualTo(1L);

        runner.runBackfill();
        long afterSecond = dsl.fetchOne("SELECT count(*) AS c FROM ops_log").get("c", Long.class);
        assertThat(afterSecond).isEqualTo(afterFirst);
    }

    @Test
    void backfillSerializesTextArraysAsJsonArrays() {
        dsl.execute("DELETE FROM ops_log");
        UUID cellId = UUID.randomUUID();
        dsl.execute(
                "INSERT INTO cells (id, content, realm, signal, topic, tags, key_points, created_by, status) "
                + "VALUES (?, ?, ?, ?, ?, ?::text[], ?::text[], ?, ?)",
                cellId, "content", "engineering", "facts", "t",
                "{tag1,tag2}", "{kp1}",
                "admin", "committed");

        runner.runBackfill();

        var row = dsl.fetchOne(
                "SELECT payload::text AS p FROM ops_log "
                + "WHERE op_type = 'add_cell' AND payload->>'id' = ?",
                cellId.toString());
        assertThat(row).isNotNull();
        String payload = row.get("p", String.class);
        assertThat(payload).contains("[\"tag1\",\"tag2\"]");
        assertThat(payload).contains("[\"kp1\"]");
        dsl.execute("DELETE FROM cells WHERE id = ?", cellId);
    }

    @Test
    void backfillSerializesJsonbAsEmbeddedJsonObject() {
        dsl.execute("DELETE FROM ops_log");
        dsl.execute("""
                INSERT INTO agents (name, focus, autonomy, tools)
                VALUES (?, ?, ?::jsonb, ?::text[])
                ON CONFLICT (name) DO UPDATE SET focus = EXCLUDED.focus,
                    autonomy = EXCLUDED.autonomy, tools = EXCLUDED.tools
                """,
                "backfill-test-agent", "test-focus",
                "{\"default\":\"suggest_only\"}", "{tool1,tool2}");

        runner.runBackfill();

        var row = dsl.fetchOne(
                "SELECT payload::text AS p FROM ops_log "
                + "WHERE op_type = 'register_agent' AND payload->>'name' = ?",
                "backfill-test-agent");
        assertThat(row).isNotNull();
        String payload = row.get("p", String.class);
        assertThat(payload).contains("\"autonomy\":{\"default\":\"suggest_only\"}");
        assertThat(payload).contains("[\"tool1\",\"tool2\"]");
        dsl.execute("DELETE FROM agents WHERE name = ?", "backfill-test-agent");
    }
}
