package com.hivemem.contradiction;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.BeforeEach;
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

    @Autowired DSLContext contradictionITSupportDsl;

    /**
     * All contradiction ITs share one Postgres instance and one cached Spring context, so rows
     * left over from one test class are visible to the next. {@code fact_contradictions.fact_a}
     * and {@code fact_b} are plain (non-cascading) foreign keys to {@code facts.id}; {@code
     * fact_contradictions.job_id} and {@code predicate_cardinality.job_id} are likewise plain
     * foreign keys to {@code contradiction_jobs.id}. Deleting {@code facts} before {@code
     * fact_contradictions} — as a subclass's own cleanup once did — throws a foreign-key violation
     * the moment any test class in the package has left a referencing row behind (e.g. {@code
     * ContradictionSchemaIT.pairIndexIsOrderIndependent}, which never cleans up after itself).
     * That failure only shows up under {@code -Dfailsafe.runOrder=alphabetical} or on CI; the
     * default filesystem run order happens to dodge it, which made it invisible until deliberately
     * checked.
     *
     * <p>Deleting in strict FK-dependency order here, once, means every present and future
     * subclass inherits a clean slate without repeating (or getting wrong) this ordering.
     */
    @BeforeEach
    void cleanContradictionTables() {
        contradictionITSupportDsl.execute("DELETE FROM predicate_cardinality");
        contradictionITSupportDsl.execute("DELETE FROM fact_contradictions");
        contradictionITSupportDsl.execute("DELETE FROM contradiction_jobs");
        contradictionITSupportDsl.execute("DELETE FROM facts");
    }

    /** A committed, open-ended fact with {@code valid_from}/{@code ingested_at} = now, confidence 1.0. */
    protected UUID insertFact(String subject, String predicate, String object) {
        return insertFact(subject, predicate, object, OffsetDateTime.now(), null, OffsetDateTime.now(), 1.0);
    }

    /** Full control over the bi-temporal columns and confidence, for tests exercising those directly. */
    protected UUID insertFact(String subject, String predicate, String object, OffsetDateTime validFrom,
            OffsetDateTime validUntil, OffsetDateTime ingestedAt, Double confidence) {
        Record r = contradictionITSupportDsl.fetchOne("""
                INSERT INTO facts (subject, predicate, "object", status, valid_from, valid_until,
                                    ingested_at, confidence)
                VALUES (?, ?, ?, 'committed', ?::timestamptz, ?::timestamptz, ?::timestamptz, ?::real)
                RETURNING id
                """, subject, predicate, object, validFrom, validUntil, ingestedAt, confidence);
        return r.get("id", UUID.class);
    }

    /** A not-yet-committed fact, invisible to {@code active_facts} until {@link #markFactCommitted}. */
    protected UUID insertPendingFact(String subject, String predicate, String object) {
        Record r = contradictionITSupportDsl.fetchOne("""
                INSERT INTO facts (subject, predicate, "object", status)
                VALUES (?, ?, ?, 'pending')
                RETURNING id
                """, subject, predicate, object);
        return r.get("id", UUID.class);
    }

    protected void markFactCommitted(UUID factId) {
        contradictionITSupportDsl.execute("UPDATE facts SET status = 'committed' WHERE id = ?", factId);
    }

    /** Records a {@code fact_contradictions} row directly, bypassing dispatch — for exclusion tests. */
    protected void recordContradiction(UUID factA, UUID factB, String subject, String predicate, String status) {
        contradictionITSupportDsl.execute("""
                INSERT INTO fact_contradictions (fact_a, fact_b, subject, predicate, status)
                VALUES (?, ?, ?, ?, ?)
                """, factA, factB, subject, predicate, status);
    }

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
