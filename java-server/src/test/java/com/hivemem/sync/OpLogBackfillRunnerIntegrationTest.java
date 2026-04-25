package com.hivemem.sync;

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
}
