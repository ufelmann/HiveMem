package com.hivemem.sync;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import org.jooq.DSLContext;
import org.jooq.Record;
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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(OpLogWriterIntegrationTest.TestConfig.class)
class OpLogWriterIntegrationTest {

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
    @Autowired OpLogWriter opLogWriter;
    @Autowired InstanceConfig instanceConfig;

    @Test
    void appendStoresEntryWithInstanceIdAndPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("hello", "world");
        payload.put("count", 42);

        UUID opId = opLogWriter.append("test_op", payload);
        assertThat(opId).isNotNull();

        Record row = dsl.fetchOne("SELECT instance_id, op_type, payload::text AS payload "
                + "FROM ops_log WHERE op_id = ?", opId);
        assertThat(row).isNotNull();
        assertThat(row.get("instance_id", UUID.class)).isEqualTo(instanceConfig.instanceId());
        assertThat(row.get("op_type", String.class)).isEqualTo("test_op");
        assertThat(row.get("payload", String.class))
                .contains("\"hello\"").contains("\"world\"")
                .contains("\"count\"").contains("42");
    }

    @Test
    void successiveAppendsHaveDistinctOpIds() {
        UUID a = opLogWriter.append("test_op", Map.of("k", "a"));
        UUID b = opLogWriter.append("test_op", Map.of("k", "b"));
        assertThat(a).isNotEqualTo(b);
    }
}
