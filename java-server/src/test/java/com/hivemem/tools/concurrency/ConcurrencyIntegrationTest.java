package com.hivemem.tools.concurrency;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.search.DrawerSearchRepository;
import com.hivemem.write.WriteToolRepository;
import com.hivemem.write.WriteToolService;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
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

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = ConcurrencyIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
@Testcontainers
class ConcurrencyIntegrationTest {

    private static final AuthPrincipal WRITER = new AuthPrincipal("writer-1", AuthRole.WRITER);
    private static final OffsetDateTime BASE_TIME = OffsetDateTime.parse("2026-04-14T09:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
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
    private WriteToolService writeToolService;

    @Autowired
    private DSLContext dslContext;

    @BeforeEach
    void resetDatabase() {
        dslContext.execute("TRUNCATE TABLE agent_diary, drawer_references, references_, blueprints, identity, agents, facts, tunnels, drawers CASCADE");
    }

    @Test
    void concurrentDrawerWritesCreateDistinctRows() throws Exception {
        List<Map<String, Object>> results = runConcurrently(6, index -> writeToolService.addDrawer(
                WRITER,
                "Concurrent drawer " + index,
                "test",
                "concurrency",
                "facts",
                "system",
                List.of("concurrency"),
                1,
                "Concurrent summary " + index,
                List.of("drawer", Integer.toString(index)),
                null,
                null,
                "committed",
                BASE_TIME.plusSeconds(index)
        ));

        assertThat(results)
                .extracting(result -> result.get("id"))
                .doesNotHaveDuplicates()
                .hasSize(6);
        assertThat(countRows("SELECT count(*) AS cnt FROM drawers WHERE wing = ? AND hall = ?", "test", "concurrency"))
                .isEqualTo(6L);
    }

    @Test
    void concurrentFactWritesDoNotLoseRows() throws Exception {
        List<Map<String, Object>> results = runConcurrently(6, index -> writeToolService.kgAdd(
                WRITER,
                "Concurrent entity " + index,
                "has_property",
                "value-" + index,
                1.0d,
                null,
                "committed",
                BASE_TIME.plusSeconds(index)
        ));

        assertThat(results)
                .extracting(result -> result.get("id"))
                .doesNotHaveDuplicates()
                .hasSize(6);
        assertThat(countRows("SELECT count(*) AS cnt FROM facts WHERE predicate = ?", "has_property"))
                .isEqualTo(6L);
    }

    @Test
    void concurrentApprovalOnSameIdsIsIdempotent() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            Map<String, Object> row = writeToolService.addDrawer(
                    WRITER,
                    "Pending drawer " + index,
                    "test",
                    "approve",
                    "facts",
                    "system",
                    List.of(),
                    1,
                    null,
                    List.of(),
                    null,
                    null,
                    "pending",
                    BASE_TIME.plusSeconds(index)
            );
            ids.add(UUID.fromString((String) row.get("id")));
        }

        List<Map<String, Object>> results = runConcurrently(2, ignored -> writeToolService.approvePending(ids, "committed"));

        assertThat(results)
                .extracting(result -> ((Number) result.get("count")).intValue())
                .containsExactlyInAnyOrder(5, 0);
        assertThat(countRows(
                "SELECT count(*) AS cnt FROM drawers WHERE wing = ? AND hall = ? AND status = 'committed'",
                "test",
                "approve"
        ))
                .isEqualTo(5L);
    }

    @Test
    void concurrentBlueprintUpdatesLeaveOneActiveVersion() throws Exception {
        List<Map<String, Object>> results = runConcurrently(2, index -> writeToolService.updateBlueprint(
                WRITER,
                "race-wing",
                "Map v" + (index + 1),
                "Narrative " + (index + 1),
                List.of("hall-" + (index + 1)),
                List.of()
        ));

        assertThat(results)
                .extracting(result -> result.get("wing"))
                .containsOnly("race-wing");
        assertThat(countRows("SELECT count(*) AS cnt FROM blueprints WHERE wing = ?", "race-wing"))
                .isEqualTo(2L);
        assertThat(countRows("SELECT count(*) AS cnt FROM blueprints WHERE wing = ? AND valid_until IS NULL", "race-wing"))
                .isEqualTo(1L);
    }

    private long countRows(String sql, Object... bindings) {
        return dslContext.fetchOne(sql, bindings).get("cnt", Long.class);
    }

    private <T> List<T> runConcurrently(int taskCount, IndexedTask<T> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(taskCount);
        CountDownLatch ready = new CountDownLatch(taskCount);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>(taskCount);
            for (int index = 0; index < taskCount; index++) {
                final int taskIndex = index;
                futures.add(executor.submit(awaitAndRun(ready, start, () -> task.run(taskIndex))));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<T> results = new ArrayList<>(taskCount);
            for (Future<T> future : futures) {
                results.add(getResult(future));
            }
            return results;
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static <T> Callable<T> awaitAndRun(CountDownLatch ready, CountDownLatch start, Callable<T> task) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to start concurrent task");
            }
            return task.call();
        };
    }

    private static <T> T getResult(Future<T> future) throws Exception {
        try {
            return future.get(15, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            throw new RuntimeException(cause);
        }
    }

    @FunctionalInterface
    interface IndexedTask<T> {
        T run(int index) throws Exception;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            WriteToolService.class,
            WriteToolRepository.class,
            DrawerSearchRepository.class,
            TestConfig.class
    })
    static class TestApplication {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        @Primary
        EmbeddingClient embeddingClient() {
            return new FixedEmbeddingClient();
        }
    }
}
