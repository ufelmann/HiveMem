package com.hivemem.embedding;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.auth.RateLimiter;
import com.hivemem.auth.TokenService;
import com.hivemem.auth.support.FixedTokenService;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddingMigrationIntegrationTest.TestConfig.class)
@Testcontainers
class EmbeddingMigrationIntegrationTest {

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
    private MockMvc mockMvc;

    @Autowired
    private DSLContext dslContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private EmbeddingMigrationService embeddingMigrationService;

    @Autowired
    private EmbeddingStateRepository stateRepository;

    @BeforeEach
    void resetDatabase() {
        rateLimiter.clearAll();
        dslContext.execute("TRUNCATE TABLE access_log, agent_diary, cell_references, references_, blueprints, identity, agents, facts, tunnels, cells CASCADE");
        dslContext.execute("REFRESH MATERIALIZED VIEW cell_popularity");
    }

    @Test
    void firstRunSavesEmbeddingInfoToIdentity() {
        // Re-run the startup check manually after truncating identity
        embeddingMigrationService.run(null);
        Optional<EmbeddingInfo> stored = stateRepository.loadStoredInfo();
        assertThat(stored).isPresent();
        assertThat(stored.get().model()).isEqualTo("test-model");
        assertThat(stored.get().dimension()).isEqualTo(1024);
    }

    @Test
    void firstRunCreatesHnswIndexForActiveDimension() {
        dslContext.execute("DROP INDEX IF EXISTS idx_cells_embedding");

        embeddingMigrationService.run(null);

        Integer count = dslContext.fetchOne(
                "SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'cells' AND indexname = 'idx_cells_embedding'")
                .get(0, Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void matchingModelStillEnsuresHnswIndexExists() {
        // Simulate an older deployment where info is stored but the index was
        // never created (first-run path predates this behavior, or an operator
        // dropped it).
        stateRepository.saveInfo(new EmbeddingInfo("test-model", 1024));
        dslContext.execute("DROP INDEX IF EXISTS idx_cells_embedding");

        embeddingMigrationService.run(null);

        Integer count = dslContext.fetchOne(
                "SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'cells' AND indexname = 'idx_cells_embedding'")
                .get(0, Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void statusShowsNoReencodingWhenModelMatches() {
        assertThat(embeddingMigrationService.isReencodingActive()).isFalse();
        assertThat(embeddingMigrationService.getProgress()).isEmpty();
    }

    @Test
    void searchWorksWhenNoReencodingActive() throws Exception {
        insertDrawer("test content for search", "eng", "facts", "infra");

        JsonNode result = callTool("writer-token", "search", Map.of(
                "query", "test content",
                "limit", 10
        ));

        assertThat(result).isNotEmpty();
    }

    @Test
    void embeddingInfoPersistedCorrectlyInIdentity() {
        stateRepository.saveInfo(new EmbeddingInfo("bge-m3", 1024));
        Optional<EmbeddingInfo> loaded = stateRepository.loadStoredInfo();
        assertThat(loaded).isPresent();
        assertThat(loaded.get().model()).isEqualTo("bge-m3");
        assertThat(loaded.get().dimension()).isEqualTo(1024);
    }

    @Test
    void progressTrackingWorksCorrectly() {
        stateRepository.saveProgress(42, 100);
        Optional<String> progress = stateRepository.loadProgress();
        assertThat(progress).isPresent();
        assertThat(progress.get()).isEqualTo("42/100");

        stateRepository.clearProgress();
        assertThat(stateRepository.loadProgress()).isEmpty();
    }

    @Test
    void countDrawersWithContentReturnsCorrectCount() {
        insertDrawer("content A", "eng", "facts", "infra");
        insertDrawer("content B", "eng", "facts", "infra");

        int count = stateRepository.countCellsWithContent();
        assertThat(count).isEqualTo(2);
    }

    @Test
    void fetchDrawerBatchReturnsBatchedResults() {
        insertDrawer("batch content 1", "eng", "facts", "infra");
        insertDrawer("batch content 2", "eng", "facts", "infra");
        insertDrawer("batch content 3", "eng", "facts", "infra");

        // Target dimension 999 differs from the drawers' actual (1024) dimension, so the
        // "still needs work" predicate matches all three rows, same as a real reencode.
        var batch1 = stateRepository.fetchCellBatch(null, 999, 2, false);
        assertThat(batch1).hasSize(2);

        var afterId = batch1.get(batch1.size() - 1).id();
        var batch2 = stateRepository.fetchCellBatch(afterId, 999, 2, false);
        assertThat(batch2).hasSize(1);
    }

    @Test
    void reencodesNonCommittedCells_soTheNonPartialIndexCanBeBuilt() {
        // The default HNSW index is bound to dimension 1024 (test-model); drop it so a
        // 384-dim insert below doesn't trip the expression-index cast.
        stateRepository.dropEmbeddingIndex();
        UUID pending = insertCell("pending", "some pending content", null, dim384Vector());
        UUID rejected = insertCell("rejected", "some rejected content", null, dim384Vector());

        var batch = stateRepository.fetchCellBatch(null, 1024, 100, false);

        assertThat(batch.stream().anyMatch(r -> r.id().equals(pending))).isTrue();
        assertThat(batch.stream().anyMatch(r -> r.id().equals(rejected))).isTrue();
        assertThat(batch.stream().filter(r -> r.id().equals(pending))
                .findFirst().orElseThrow().status()).isEqualTo("pending");
    }

    @Test
    void forceAll_selectsRowsWhoseDimensionAlreadyMatches() {
        // The default HNSW index is bound to dimension 1024 (test-model); drop it so a
        // 384-dim insert below doesn't trip the expression-index cast.
        stateRepository.dropEmbeddingIndex();
        UUID id = insertCell("committed", "content", null, dim384Vector());

        assertThat(stateRepository.fetchCellBatch(null, 384, 100, false).stream()
                .noneMatch(r -> r.id().equals(id))).as("without forceAll the row is skipped").isTrue();
        assertThat(stateRepository.fetchCellBatch(null, 384, 100, true).stream()
                .anyMatch(r -> r.id().equals(id))).as("with forceAll the row is selected").isTrue();
    }

    @Test
    void reencodesNonCommittedFacts_soTheNonPartialIndexCanBeBuilt() {
        // The default HNSW index is bound to dimension 1024 (test-model); drop it so a
        // 384-dim insert below doesn't trip the expression-index cast.
        stateRepository.dropFactsEmbeddingIndex();
        UUID pending = insertFact("pending", "subj-pending", "pred", "obj", dim384Vector());
        UUID rejected = insertFact("rejected", "subj-rejected", "pred", "obj", dim384Vector());

        var batch = stateRepository.fetchFactBatch(null, 1024, 100, false);

        assertThat(batch.stream().anyMatch(r -> r.id().equals(pending))).isTrue();
        assertThat(batch.stream().anyMatch(r -> r.id().equals(rejected))).isTrue();
    }

    @Test
    void updateEmbeddingWritesNewVector() {
        // Drop the HNSW index so that we can update with a different dimension
        // (this mirrors the production flow: index is dropped before re-encoding)
        stateRepository.dropEmbeddingIndex();

        insertDrawer("embedding update test", "eng", "facts", "infra");
        var rows = stateRepository.fetchCellBatch(null, 999, 1, false);
        assertThat(rows).hasSize(1);

        FixedEmbeddingClient client = new FixedEmbeddingClient(512, "new-model");
        var embedding = client.encodeDocument("embedding update test");
        stateRepository.updateEmbedding(rows.getFirst().id(), embedding);

        var result = dslContext.fetchOne(
                "SELECT array_length(embedding::real[], 1) AS dim FROM cells WHERE id = ?",
                rows.getFirst().id());
        assertThat(result).isNotNull();
        assertThat(result.get("dim", Integer.class)).isEqualTo(512);
    }

    @Test
    void advisoryLockPreventsParallelReencoding() {
        boolean first = stateRepository.tryAdvisoryLock(99999L);
        assertThat(first).isTrue();

        stateRepository.releaseAdvisoryLock(99999L);

        boolean afterRelease = stateRepository.tryAdvisoryLock(99999L);
        assertThat(afterRelease).isTrue();
        stateRepository.releaseAdvisoryLock(99999L);
    }

    @Test
    void indexDropAndRecreateWorks() {
        insertDrawer("index test content", "eng", "facts", "infra");

        stateRepository.dropEmbeddingIndex();
        insertDrawer("after drop content", "eng", "facts", "infra");

        stateRepository.createEmbeddingIndex(1024);

        var result = dslContext.fetchOne("""
                SELECT count(*) AS cnt FROM pg_indexes
                WHERE tablename = 'cells' AND indexname = 'idx_cells_embedding'
                """);
        assertThat(result).isNotNull();
        assertThat(result.get("cnt", Number.class).intValue()).isEqualTo(1);
    }

    @Test
    void flexibleVectorDimensionAcceptsDifferentSizes() {
        // The HNSW index enforces a fixed dimension; drop it first to simulate the
        // mid-reencoding state where the column accepts any dimension.
        stateRepository.dropEmbeddingIndex();

        UUID id1 = UUID.randomUUID();
        dslContext.execute("""
                INSERT INTO cells (id, content, embedding, realm, signal, topic, status, created_by, valid_from)
                VALUES (?, 'small vec', ?::vector, 'eng', 'facts', 'test', 'committed', 'test', now())
                """, id1, new Float[]{0.1f, 0.2f, 0.3f});

        UUID id2 = UUID.randomUUID();
        Float[] largeVec = new Float[2048];
        for (int i = 0; i < 2048; i++) largeVec[i] = 0.01f;
        dslContext.execute("""
                INSERT INTO cells (id, content, embedding, realm, signal, topic, status, created_by, valid_from)
                VALUES (?, 'large vec', ?::vector, 'eng', 'facts', 'test', 'committed', 'test', now())
                """, id2, largeVec);

        var result = dslContext.fetchOne("SELECT count(*) AS cnt FROM cells WHERE id IN (?, ?)", id1, id2);
        assertThat(result.get("cnt", Number.class).intValue()).isEqualTo(2);
    }

    @Test
    void dropsEveryVectorIndexOnCells_notJustTheKnownName() {
        dslContext.execute("CREATE INDEX idx_legacy_embedding ON cells USING hnsw "
                + "((embedding::vector(384)) vector_cosine_ops)");
        // Pin the scope: a vector index on a different table, and a non-vector index on the
        // same table, must both survive — otherwise an implementation that ignores the
        // `table` argument, or drops every vector index in the database, or drops every
        // index regardless of am, would pass this test identically.
        stateRepository.createFactsEmbeddingIndex(1024);
        dslContext.execute("CREATE INDEX idx_cells_realm_btree ON cells (realm)");

        stateRepository.dropVectorIndexes("cells");

        Integer remaining = dslContext.fetchOne(
                "SELECT count(*)::int FROM pg_index i "
              + "JOIN pg_class c ON c.oid = i.indexrelid "
              + "JOIN pg_am a ON a.oid = c.relam "
              + "WHERE a.amname IN ('hnsw','ivfflat') AND i.indrelid = 'cells'::regclass")
            .get(0, Integer.class);
        assertThat(remaining).isEqualTo(0);

        Integer factsIndexCount = dslContext.fetchOne(
                "SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'facts' AND indexname = 'idx_facts_embedding'")
                .get(0, Integer.class);
        assertThat(factsIndexCount).isEqualTo(1);

        Integer btreeIndexCount = dslContext.fetchOne(
                "SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'cells' AND indexname = 'idx_cells_realm_btree'")
                .get(0, Integer.class);
        assertThat(btreeIndexCount).isEqualTo(1);
    }

    @Test
    void rankedSearchFunctionExistsWithActiveDimension() {
        int dim = embeddingMigrationService.getCurrentDimension();
        Float[] zeros = new Float[dim];
        java.util.Arrays.fill(zeros, 0.0f);

        // Should not throw "expected N dimensions, not M".
        dslContext.execute(
                "SELECT * FROM ranked_search(?::vector, ?, NULL, NULL, NULL, 1, 0.35, 0.15, 0.20, 0.15, 0.15)",
                zeros, "");
    }

    private void insertDrawer(String content, String wing, String hall, String room) {
        FixedEmbeddingClient client = new FixedEmbeddingClient();
        var embedding = client.encodeDocument(content);
        Float[] embeddingArray = embedding.toArray(Float[]::new);
        dslContext.execute("""
                INSERT INTO cells (id, content, embedding, realm, signal, topic, status, created_by, valid_from)
                VALUES (?, ?, ?::vector, ?, ?, ?, 'committed', 'test', now())
                """, UUID.randomUUID(), content, embeddingArray, wing, hall, room);
    }

    private UUID insertCell(String status, String content, String summary, Float[] embedding) {
        UUID id = UUID.randomUUID();
        dslContext.execute("""
                INSERT INTO cells (id, content, summary, embedding, realm, signal, topic, status, created_by, valid_from)
                VALUES (?, ?, ?, ?::vector, 'eng', 'facts', 'infra', ?, 'test', now())
                """, id, content, summary, embedding, status);
        return id;
    }

    private static Float[] dim384Vector() {
        Float[] v = new Float[384];
        java.util.Arrays.fill(v, 0.1f);
        return v;
    }

    private UUID insertFact(String status, String subject, String predicate, String object, Float[] embedding) {
        UUID id = UUID.randomUUID();
        dslContext.execute("""
                INSERT INTO facts (id, subject, predicate, "object", embedding, status, created_by, valid_from)
                VALUES (?, ?, ?, ?, ?::vector, ?, 'test', now())
                """, id, subject, predicate, object, embedding, status);
        return id;
    }

    private JsonNode callTool(String token, String toolName, Map<String, Object> arguments) throws Exception {
        var result = mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "jsonrpc", "2.0",
                                "id", 1,
                                "method", "tools/call",
                                "params", Map.of(
                                        "name", toolName,
                                        "arguments", arguments
                                )
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String textContent = body.path("result").path("content").get(0).path("text").asText();
        return objectMapper.readTree(textContent);
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
