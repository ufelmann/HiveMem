package com.hivemem.contradiction;

import static org.assertj.core.api.Assertions.assertThat;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
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

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(ContradictionJobRepositoryIT.TestConfig.class)
class ContradictionJobRepositoryIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        @Primary
        EmbeddingClient testEmbeddingClient() {
            return new FixedEmbeddingClient();
        }
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
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired DSLContext dsl;
    @Autowired ContradictionJobRepository jobs;

    @BeforeEach
    void cleanUp() {
        dsl.execute("DELETE FROM contradiction_jobs");
    }

    @Test
    void claimFlipsAwaitingToProcessingExactlyOnce() {
        UUID id = jobs.create(UUID.randomUUID(), "pairs", 5);
        assertThat(jobs.claimByJobId(id)).isTrue();
        assertThat(jobs.claimByJobId(id)).isFalse();
    }

    @Test
    void findStaleCoversAwaitingAndProcessing() {
        UUID awaiting = jobs.create(UUID.randomUUID(), "pairs", 1);
        UUID processing = jobs.create(UUID.randomUUID(), "cardinality", 1);
        jobs.claimByJobId(processing);
        age(awaiting);
        age(processing);

        List<ContradictionJobRepository.Job> stale = jobs.findStale(Duration.ofMinutes(10), 10);

        assertThat(stale).extracting(ContradictionJobRepository.Job::id)
                .containsExactlyInAnyOrder(awaiting, processing);
    }

    @Test
    void findStaleIgnoresTerminalAndFreshJobs() {
        UUID fresh = jobs.create(UUID.randomUUID(), "pairs", 1);
        UUID done = jobs.create(UUID.randomUUID(), "pairs", 1);
        jobs.markDone(done);
        age(done);
        assertThat(jobs.findStale(Duration.ofMinutes(10), 10))
                .extracting(ContradictionJobRepository.Job::id)
                .doesNotContain(fresh, done);
    }

    @Test
    void countTodayCountsFromUtcMidnightOnly() {
        jobs.create(UUID.randomUUID(), "pairs", 1);
        UUID yesterday = jobs.create(UUID.randomUUID(), "pairs", 1);
        dsl.execute("UPDATE contradiction_jobs SET created_at = now() - interval '2 days' WHERE id = ?",
                yesterday);
        assertThat(jobs.countToday()).isEqualTo(1);
    }

    @Test
    void findByRunIdMatchesTheCallbackJoinKey() {
        UUID id = jobs.create(UUID.randomUUID(), "cardinality", 2);
        jobs.attachRunId(id, "run-abc");
        assertThat(jobs.findByRunId("run-abc")).isPresent()
                .get().extracting(ContradictionJobRepository.Job::kind).isEqualTo("cardinality");
        assertThat(jobs.findByRunId("nope")).isEmpty();
        assertThat(jobs.findByRunId(null)).isEmpty();
    }

    @Test
    void deleteRemovesACompensatedJob() {
        UUID id = jobs.create(UUID.randomUUID(), "pairs", 1);
        jobs.delete(id);
        assertThat(jobs.countToday()).isZero();
    }

    private void age(UUID jobId) {
        dsl.execute("UPDATE contradiction_jobs SET updated_at = now() - interval '1 hour' WHERE id = ?",
                jobId);
    }
}
