package com.hivemem.contradiction;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import java.util.UUID;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(ContradictionSchemaIT.TestConfig.class)
class ContradictionSchemaIT {

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

    /** The jobs table must accept 'processing' — it mirrors consumption_jobs, whose claim() writes it. */
    @Test
    void jobsStatusAcceptsProcessing() {
        UUID correlationId = UUID.randomUUID();
        dsl.execute("INSERT INTO contradiction_jobs (correlation_id, kind, item_count) "
                + "VALUES (?, 'pairs', 3)", correlationId);
        int updated = dsl.execute(
                "UPDATE contradiction_jobs SET status='processing' WHERE correlation_id=?", correlationId);
        assertThat(updated).isEqualTo(1);
    }

    /** The pair index is order-independent: (A,B) and (B,A) are one pair. */
    @Test
    void pairIndexIsOrderIndependent() {
        UUID a = insertFact("s-pair", "p", "x");
        UUID b = insertFact("s-pair", "p", "y");
        dsl.execute("INSERT INTO fact_contradictions (fact_a, fact_b, subject, predicate) "
                + "VALUES (?, ?, 's-pair', 'p')", a, b);
        assertThatThrownBy(() -> dsl.execute(
                "INSERT INTO fact_contradictions (fact_a, fact_b, subject, predicate) "
                        + "VALUES (?, ?, 's-pair', 'p')", b, a))
                .hasMessageContaining("ux_fact_contradictions_pair");
    }

    /** A fact cannot contradict itself — the pair index alone would accept (A,A). */
    @Test
    void selfPairIsRejected() {
        UUID a = insertFact("s-self", "p", "x");
        assertThatThrownBy(() -> dsl.execute(
                "INSERT INTO fact_contradictions (fact_a, fact_b, subject, predicate) "
                        + "VALUES (?, ?, 's-self', 'p')", a, a))
                .hasMessageContaining("ck_fact_contradictions_distinct");
    }

    /** cardinality is nullable: a row exists from reservation, before any verdict. */
    @Test
    void cardinalityIsNullableUntilDecided() {
        dsl.execute("INSERT INTO predicate_cardinality (predicate) VALUES ('reserved_only')");
        Record reserved = dsl.fetchOne(
                "SELECT status, cardinality FROM predicate_cardinality WHERE predicate='reserved_only'");
        assertThat(reserved.get("status", String.class)).isEqualTo("in_flight");
        assertThat(reserved.get("cardinality", String.class)).isNull();
    }

    private UUID insertFact(String subject, String predicate, String object) {
        return dsl.fetchOne("""
                INSERT INTO facts (subject, predicate, "object", status)
                VALUES (?, ?, ?, 'committed') RETURNING id
                """, subject, predicate, object).get("id", UUID.class);
    }
}
