package com.hivemem.queen;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for the two Vistierie completion webhooks Task 13 adds to {@link
 * VistierieWebhookController}: {@code /vistierie/contradiction/done} (pair judge) and {@code
 * /vistierie/cardinality/done} (cardinality judge). Follows {@link VistierieIngestIT}'s shape —
 * a real Postgres-backed Spring context plus MockMvc — rather than {@code ContradictionITSupport}
 * (package-private, different package).
 *
 * <p>The "feature disabled" cases (missing bean -> 200, bad token -> 401) are deliberately NOT
 * here: they are covered in {@link VistierieWebhookControllerTest} against a standalone MockMvc
 * with a mocked {@code ObjectProvider}. The real guard against a regression to plain constructor
 * injection of {@code ContradictionService} is {@link VistierieIngestIT}, not
 * {@code HiveMemApplicationTest}: {@code HiveMemApplicationTest} sets
 * {@code spring.main.lazy-initialization=true}, so it never actually instantiates this
 * controller and would not catch such a regression. {@code VistierieIngestIT} boots a full,
 * eager (non-lazy) Spring context that leaves {@code hivemem.contradiction.enabled} at its
 * {@code application.yml} default of {@code false} — so its existing, unmodified boot already
 * proves a disabled install starts up with the {@code ObjectProvider} wiring in place.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(ContradictionWebhookIT.TestConfig.class)
class ContradictionWebhookIT {

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
        registry.add("hivemem.queen.enabled", () -> "true");
        registry.add("hivemem.queen.contradiction-webhook-token", () -> "ctok");
        registry.add("hivemem.contradiction.enabled", () -> "true");
    }

    @Autowired DSLContext db;
    @Autowired MockMvc mvc;

    // ---- fixture helpers -------------------------------------------------

    private UUID insertFact(String subject, String predicate, String object) {
        Record r = db.fetchOne("""
                INSERT INTO facts (subject, predicate, "object", status, valid_from, ingested_at, confidence)
                VALUES (?, ?, ?, 'committed', now(), now(), 1.0)
                RETURNING id
                """, subject, predicate, object);
        return r.get("id", UUID.class);
    }

    private UUID createJob(String kind, String runId) {
        Record r = db.fetchOne("""
                INSERT INTO contradiction_jobs (correlation_id, kind, item_count, status, vistierie_run_id)
                VALUES (?, ?, 1, 'awaiting', ?)
                RETURNING id
                """, UUID.randomUUID(), kind, runId);
        return r.get("id", UUID.class);
    }

    private UUID insertPairRow(UUID jobId, UUID factA, UUID factB, String subject, String predicate,
            String status) {
        Record r = db.fetchOne("""
                INSERT INTO fact_contradictions (fact_a, fact_b, subject, predicate, job_id, status, attempts)
                VALUES (?, ?, ?, ?, ?, ?, 1)
                RETURNING id
                """, factA, factB, subject, predicate, jobId, status);
        return r.get("id", UUID.class);
    }

    private void insertCardinalityRow(UUID jobId, String predicate, String status) {
        db.execute("""
                INSERT INTO predicate_cardinality (predicate, status, attempts, job_id)
                VALUES (?, ?, 1, ?)
                """, predicate, status, jobId);
    }

    private String jobStatus(UUID jobId) {
        return db.fetchOne("SELECT status FROM contradiction_jobs WHERE id = ?", jobId)
                .get("status", String.class);
    }

    private Record pairRow(UUID id) {
        return db.fetchOne(
                "SELECT status, judge_confidence, rationale FROM fact_contradictions WHERE id = ?", id);
    }

    private Record cardinalityRow(String predicate) {
        return db.fetchOne(
                "SELECT status, cardinality FROM predicate_cardinality WHERE predicate = ?", predicate);
    }

    private int countRows(String table) {
        return db.fetchOne("SELECT count(*) AS c FROM " + table).get("c", Integer.class);
    }

    // ---- tests -------------------------------------------------------------

    @Test
    void aPairCallbackRecordsVerdictsAndNeverTouchesCardinality() throws Exception {
        // Pre-existing, distinguishable cardinality row that a wrong-table write would corrupt.
        UUID cardinalityJob = createJob("cardinality", "unrelated-card-run");
        insertCardinalityRow(cardinalityJob, "untouched_predicate", "in_flight");
        int cardinalityRowsBefore = countRows("predicate_cardinality");

        UUID factA = insertFact("alice-" + UUID.randomUUID(), "favorite_color", "blue");
        UUID factB = insertFact(factA.toString(), "favorite_color", "red"); // subject unused for this test
        UUID pairJob = createJob("pairs", "pair-run-1");
        UUID pairId = insertPairRow(pairJob, factA, factB, "alice", "favorite_color", "in_flight");

        String body = """
                {"run_id":"pair-run-1","status":"done","output":{"verdicts":[
                  {"pair_id":"%s","contradiction":true,"confidence":0.91,"rationale":"genuinely conflicting"}
                ]}}""".formatted(pairId);

        mvc.perform(post("/vistierie/contradiction/done")
                        .header("Authorization", "Bearer ctok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Intended table DID get the write: in_flight -> pending, per ContradictionRepository#recordVerdict.
        Record pair = pairRow(pairId);
        assertThat(pair.get("status", String.class)).isEqualTo("pending");
        // judge_confidence is a Postgres REAL (32-bit float) column, so the round-tripped value is
        // only equal to the double literal within float precision, not bit-for-bit.
        assertThat(pair.get("judge_confidence", Double.class)).isCloseTo(0.91, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(pair.get("rationale", String.class)).isEqualTo("genuinely conflicting");
        assertThat(jobStatus(pairJob)).isEqualTo("done");

        // Untouched table: the pre-existing cardinality row and the table's row count are unchanged.
        Record cardinality = cardinalityRow("untouched_predicate");
        assertThat(cardinality.get("status", String.class)).isEqualTo("in_flight");
        assertThat(cardinality.get("cardinality", String.class)).isNull();
        assertThat(countRows("predicate_cardinality")).isEqualTo(cardinalityRowsBefore);
    }

    @Test
    void aCardinalityCallbackRecordsVerdictsAndNeverTouchesPairs() throws Exception {
        // Pre-existing, distinguishable pair row that a wrong-table write would corrupt.
        UUID pairsJob = createJob("pairs", "unrelated-pair-run");
        UUID untouchedFactA = insertFact("bob-" + UUID.randomUUID(), "job_title", "engineer");
        UUID untouchedFactB = insertFact(untouchedFactA.toString(), "job_title", "manager");
        UUID untouchedPairId = insertPairRow(pairsJob, untouchedFactA, untouchedFactB, "bob", "job_title",
                "in_flight");
        int pairRowsBefore = countRows("fact_contradictions");

        UUID cardinalityJob = createJob("cardinality", "card-run-1");
        insertCardinalityRow(cardinalityJob, "eye_color", "in_flight");

        String body = """
                {"run_id":"card-run-1","status":"done","output":{"verdicts":[
                  {"predicate":"eye_color","cardinality":"single_valued","confidence":0.77,"rationale":"one eye color"}
                ]}}""";

        mvc.perform(post("/vistierie/cardinality/done")
                        .header("Authorization", "Bearer ctok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Intended table DID get the write.
        Record cardinality = cardinalityRow("eye_color");
        assertThat(cardinality.get("status", String.class)).isEqualTo("decided");
        assertThat(cardinality.get("cardinality", String.class)).isEqualTo("single_valued");
        assertThat(jobStatus(cardinalityJob)).isEqualTo("done");

        // Untouched table: the pre-existing pair row and the table's row count are unchanged.
        Record pair = pairRow(untouchedPairId);
        assertThat(pair.get("status", String.class)).isEqualTo("in_flight");
        assertThat(countRows("fact_contradictions")).isEqualTo(pairRowsBefore);
    }

    @Test
    void anUnknownRunIdIsAcknowledgedWithTwoHundred() throws Exception {
        String body = """
                {"run_id":"no-such-run","status":"done","output":{"verdicts":[]}}""";

        mvc.perform(post("/vistierie/contradiction/done")
                        .header("Authorization", "Bearer ctok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void aMalformedPayloadFailsTheJobButStillAnswersTwoHundred() throws Exception {
        UUID factA = insertFact("carol-" + UUID.randomUUID(), "hair_color", "brown");
        UUID factB = insertFact(factA.toString(), "hair_color", "black");
        UUID pairJob = createJob("pairs", "malformed-run");
        UUID pairId = insertPairRow(pairJob, factA, factB, "carol", "hair_color", "in_flight");

        // status != "done" -> the domain "malformed" case: the job fails, the row is not judged, but
        // the webhook itself must never answer anything other than 200 (else Vistierie retries).
        String body = """
                {"run_id":"malformed-run","status":"failed","output":null}""";

        mvc.perform(post("/vistierie/contradiction/done")
                        .header("Authorization", "Bearer ctok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        assertThat(jobStatus(pairJob)).isEqualTo("failed");
        // attempts=1 < default maxAttempts=3 -> retryable, not deferred; and definitely not left in_flight.
        assertThat(pairRow(pairId).get("status", String.class)).isEqualTo("retryable");
    }

    @Test
    void aDuplicateDeliveryIsANoOp() throws Exception {
        UUID factA = insertFact("dave-" + UUID.randomUUID(), "shirt_color", "green");
        UUID factB = insertFact(factA.toString(), "shirt_color", "yellow");
        UUID pairJob = createJob("pairs", "dup-run-1");
        UUID pairId = insertPairRow(pairJob, factA, factB, "dave", "shirt_color", "in_flight");

        String body = """
                {"run_id":"dup-run-1","status":"done","output":{"verdicts":[
                  {"pair_id":"%s","contradiction":true,"confidence":0.5,"rationale":"first delivery"}
                ]}}""".formatted(pairId);

        mvc.perform(post("/vistierie/contradiction/done")
                        .header("Authorization", "Bearer ctok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        assertThat(jobStatus(pairJob)).isEqualTo("done");
        assertThat(pairRow(pairId).get("judge_confidence", Double.class)).isEqualTo(0.5);

        // Second delivery of the SAME payload for the SAME (now 'done') job: claim() fails, so this
        // must be a true no-op, not a second application of the verdict.
        String secondBody = """
                {"run_id":"dup-run-1","status":"done","output":{"verdicts":[
                  {"pair_id":"%s","contradiction":false,"confidence":0.99,"rationale":"should never apply"}
                ]}}""".formatted(pairId);

        mvc.perform(post("/vistierie/contradiction/done")
                        .header("Authorization", "Bearer ctok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondBody))
                .andExpect(status().isOk());

        assertThat(jobStatus(pairJob)).isEqualTo("done");
        Record pair = pairRow(pairId);
        assertThat(pair.get("status", String.class)).isEqualTo("pending");
        assertThat(pair.get("judge_confidence", Double.class)).isEqualTo(0.5);
        assertThat(pair.get("rationale", String.class)).isEqualTo("first delivery");
    }
}
