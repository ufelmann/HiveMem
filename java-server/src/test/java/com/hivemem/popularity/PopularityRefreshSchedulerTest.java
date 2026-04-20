package com.hivemem.popularity;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.auth.RateLimiter;
import com.hivemem.auth.TokenService;
import com.hivemem.auth.support.FixedTokenService;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PopularityRefreshSchedulerTest.TestConfig.class)
@Testcontainers
class PopularityRefreshSchedulerTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem")
            .withUsername("hivemem")
            .withPassword("hivemem")
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

    @Autowired
    private PopularityRefreshScheduler scheduler;

    @Autowired
    private DSLContext dslContext;

    @Autowired
    private RateLimiter rateLimiter;

    @BeforeEach
    void resetDatabase() {
        rateLimiter.clearAll();
        dslContext.execute("TRUNCATE TABLE access_log, agent_diary, cell_references, references_, blueprints, identity, agents, facts, tunnels, cells CASCADE");
        dslContext.execute("REFRESH MATERIALIZED VIEW cell_popularity");
    }

    @Test
    void schedulerBeanIsWired() {
        assertThat(scheduler).isNotNull();
    }

    @Test
    void refreshDoesNotThrow() {
        assertThatCode(() -> scheduler.refresh()).doesNotThrowAnyException();
    }

    @Test
    void refreshUpdatesPopularityView() {
        // Insert a drawer and log an access, then refresh — MV should reflect the data
        java.util.UUID drawerId = java.util.UUID.randomUUID();
        com.hivemem.embedding.FixedEmbeddingClient client = new FixedEmbeddingClient();
        Float[] embedding = client.encodeDocument("test content").toArray(Float[]::new);
        dslContext.execute("""
                INSERT INTO cells (id, content, embedding, realm, signal, topic, status, created_by, valid_from)
                VALUES (?, ?, ?::vector, 'eng', 'infra', 'facts', 'committed', 'test', now())
                """, drawerId, "test content", embedding);

        dslContext.execute("INSERT INTO access_log (cell_id, accessed_by) VALUES (?, 'test')", drawerId);

        scheduler.refresh();

        Long accessCount = dslContext.fetchOne("""
                SELECT access_count FROM cell_popularity WHERE cell_id = ?
                """, drawerId).get("access_count", Long.class);
        assertThat(accessCount).isEqualTo(1L);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        @Primary
        EmbeddingClient testEmbeddingClient() {
            return new FixedEmbeddingClient();
        }

        @Bean
        @Primary
        TokenService tokenService() {
            return new FixedTokenService(token -> switch (token) {
                case "writer-token" -> Optional.of(new AuthPrincipal("writer-1", AuthRole.WRITER));
                case "admin-token" -> Optional.of(new AuthPrincipal("admin-1", AuthRole.ADMIN));
                default -> Optional.empty();
            });
        }
    }
}
