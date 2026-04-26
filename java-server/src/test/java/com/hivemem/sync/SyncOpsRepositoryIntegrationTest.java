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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(SyncOpsRepositoryIntegrationTest.TestConfig.class)
class SyncOpsRepositoryIntegrationTest {

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
    @Autowired OpLogWriter opLogWriter;
    @Autowired SyncOpsRepository syncOpsRepository;

    @Test
    void findOpsAfterReturnsOpsInOrder() {
        opLogWriter.append("test_op", Map.of("k", "v1"));
        opLogWriter.append("test_op", Map.of("k", "v2"));

        List<OpDto> all = syncOpsRepository.findOpsAfter(0);
        assertThat(all).hasSizeGreaterThanOrEqualTo(2);
        assertThat(all.get(0).seq()).isLessThan(all.get(1).seq());
    }

    @Test
    void findOpsAfterFiltersBySince() {
        opLogWriter.append("test_op", Map.of("k", "before"));
        long seqBefore = dsl.fetchOne("SELECT max(seq) AS s FROM ops_log").get("s", Long.class);
        opLogWriter.append("test_op", Map.of("k", "after"));

        List<OpDto> ops = syncOpsRepository.findOpsAfter(seqBefore);
        assertThat(ops).hasSize(1);
        assertThat(ops.get(0).payload().get("k").asText()).isEqualTo("after");
    }

    @Test
    void findOpByIdReturnsCorrectOp() {
        UUID opId = opLogWriter.append("test_op", Map.of("key", "val"));
        OpDto found = syncOpsRepository.findOpById(opId);
        assertThat(found).isNotNull();
        assertThat(found.opId()).isEqualTo(opId);
        assertThat(found.opType()).isEqualTo("test_op");
        assertThat(found.payload().get("key").asText()).isEqualTo("val");
    }
}
