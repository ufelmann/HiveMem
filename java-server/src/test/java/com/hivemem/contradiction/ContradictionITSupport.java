package com.hivemem.contradiction;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared Testcontainers + Spring Boot wiring for the contradiction package's integration tests.
 *
 * <p>Following the precedent of {@code consumption.ConsumptionITSupport}: subclasses extend this
 * instead of repeating the {@code @SpringBootTest}/{@code @Import} preamble. That matters beyond
 * avoiding copy-paste — two test classes that each declare their own (even identical)
 * {@code @TestConfiguration} inner class get different Spring context-cache keys, so Spring boots a
 * separate application context per test class instead of reusing one.
 *
 * <p>The Postgres container deliberately does <em>not</em> use {@code @Testcontainers}/
 * {@code @Container}: that JUnit 5 lifecycle stops the container after the owning test class's
 * tests finish, even for a field inherited from this shared superclass. The Spring context cache,
 * however, is a JVM-wide cache keyed on merged configuration — it happily reuses the context (and
 * the HikariCP pool inside it) for a later test class, unaware that the container the pool is
 * pointing at has just been torn down. The result: the first IT class to run passes, and every
 * later one in the same JVM fails with "Connection refused" once its Hikari pool tries the dead
 * port. Starting the container once in a static initializer (the Testcontainers "singleton
 * container" pattern) avoids this: nothing stops it early, and Ryuk reaps it when the JVM exits.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(ContradictionITSupport.TestConfig.class)
abstract class ContradictionITSupport {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        @Primary
        EmbeddingClient testEmbeddingClient() {
            return new FixedEmbeddingClient();
        }
    }

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null
                            ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig())
                            .withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }
}
