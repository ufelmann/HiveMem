package com.hivemem.sync;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.write.WriteToolService;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(WriteHandlerOpLogIntegrationTest.TestConfig.class)
class WriteHandlerOpLogIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        @Primary
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
    @Autowired WriteToolService service;

    private AuthPrincipal admin() { return new AuthPrincipal("admin", AuthRole.ADMIN); }

    private long opCount(String opType) {
        return dsl.fetchOne("SELECT count(*) AS c FROM ops_log WHERE op_type = ?", opType)
                .get("c", Long.class);
    }

    private String latestPayload(String opType) {
        return dsl.fetchOne("SELECT payload::text AS p FROM ops_log "
                + "WHERE op_type = ? ORDER BY seq DESC LIMIT 1", opType)
                .get("p", String.class);
    }

    @Test
    void addCellEmitsAddCellOp() {
        long before = opCount("add_cell");
        Map<String, Object> result = service.addCell(
                admin(), "hello world", "engineering", "facts", "test",
                null, List.of(), 1, "summary", List.of(), null, null, null, null, null);

        assertThat(opCount("add_cell")).isEqualTo(before + 1);
        String payload = latestPayload("add_cell");
        assertThat(payload).contains((String) result.get("id"));
        assertThat(payload).contains("\"engineering\"");
        assertThat(payload).contains("\"facts\"");
        assertThat(payload).contains("\"hello world\"");
    }

    @Test
    void reviseCellEmitsReviseCellOp() {
        Map<String, Object> created = service.addCell(
                admin(), "original", "engineering", "facts", "topic",
                null, List.of(), 1, "summary", List.of(), null, null, null, null, null);
        UUID cellId = UUID.fromString((String) created.get("id"));

        long before = opCount("revise_cell");
        service.reviseCell(admin(), cellId, "revised content", "revised summary");

        assertThat(opCount("revise_cell")).isEqualTo(before + 1);
        String payload = latestPayload("revise_cell");
        assertThat(payload).contains(cellId.toString());
        assertThat(payload).contains("\"revised content\"");
        assertThat(payload).contains("\"revised summary\"");
    }

    @Test
    void reclassifyCellEmitsReclassifyOp() {
        Map<String, Object> created = service.addCell(
                admin(), "x", "engineering", "facts", "topic",
                null, List.of(), 1, "s", List.of(), null, null, null, null, null);
        UUID cellId = UUID.fromString((String) created.get("id"));

        long before = opCount("reclassify_cell");
        service.reclassifyCell(admin(), cellId, "personal", "newtopic", "events");

        assertThat(opCount("reclassify_cell")).isEqualTo(before + 1);
        String payload = latestPayload("reclassify_cell");
        assertThat(payload).contains(cellId.toString());
        assertThat(payload).contains("\"personal\"").contains("\"newtopic\"").contains("\"events\"");
    }
}
