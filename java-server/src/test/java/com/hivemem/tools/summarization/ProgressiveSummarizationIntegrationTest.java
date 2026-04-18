package com.hivemem.tools.summarization;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.drawers.DrawerReadRepository;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.search.DrawerSearchRepository;
import com.hivemem.search.KgSearchRepository;
import com.hivemem.tools.read.ReadToolService;
import com.hivemem.write.AdminToolRepository;
import com.hivemem.write.AdminToolService;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for progressive summarization (L0-L3 layers).
 *
 * <p>Covers persistence of all four layers, optional layers, actionability
 * constraint validation, duplicate checking with threshold sensitivity,
 * and layer preservation across drawer revisions.
 */
@SpringBootTest(
        classes = ProgressiveSummarizationIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
@Testcontainers
@SuppressWarnings("unchecked")
class ProgressiveSummarizationIntegrationTest {

    private static final AuthPrincipal WRITER = new AuthPrincipal("writer-1", AuthRole.WRITER);
    private static final OffsetDateTime BASE_TIME = OffsetDateTime.parse("2026-04-15T10:00:00Z");

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
    private WriteToolService writeToolService;

    @Autowired
    private ReadToolService readToolService;

    @Autowired
    private DSLContext dslContext;

    @BeforeEach
    void resetDatabase() {
        dslContext.execute("TRUNCATE TABLE agent_diary, drawer_references, references_, blueprints, identity, agents, facts, tunnels, drawers CASCADE");
    }

    // -----------------------------------------------------------------------
    // 1. All L0-L3 layers are persisted and returned via getDrawer
    // -----------------------------------------------------------------------

    @Test
    void allLayersPersistedAndReturnedViaGetDrawer() {
        Map<String, Object> created = writeToolService.addDrawer(
                WRITER,
                "We decided to migrate BOGIS from Camunda 7 to Temporal. Better DX and native Go support.",
                "engineering",
                "bogis",
                "facts",
                "claude-code",
                List.of("migration", "temporal"),
                1,
                "BOGIS migrating from Camunda 7 to Temporal",
                List.of("Camunda 7 to Temporal migration", "Better DX", "Native Go support"),
                "This unblocks the Go rewrite of the orchestration layer",
                "actionable",
                "committed",
                BASE_TIME
        );

        String drawerId = (String) created.get("id");
        assertThat(drawerId).isNotNull();

        Map<String, Object> drawer = readToolService.getDrawer(WRITER,UUID.fromString(drawerId));
        assertThat(drawer).isNotNull();
        assertThat(drawer.get("content")).isEqualTo(
                "We decided to migrate BOGIS from Camunda 7 to Temporal. Better DX and native Go support.");
        assertThat(drawer.get("summary")).isEqualTo("BOGIS migrating from Camunda 7 to Temporal");
        assertThat((List<String>) drawer.get("key_points"))
                .containsExactly("Camunda 7 to Temporal migration", "Better DX", "Native Go support");
        assertThat(drawer.get("insight")).isEqualTo("This unblocks the Go rewrite of the orchestration layer");
        assertThat(drawer.get("actionability")).isEqualTo("actionable");
    }

    // -----------------------------------------------------------------------
    // 2. Drawer with only L0 (summary/key_points/insight null) is valid
    // -----------------------------------------------------------------------

    @Test
    void drawerWithOnlyL0IsValidAndStored() {
        Map<String, Object> created = writeToolService.addDrawer(
                WRITER,
                "Minimal drawer without progressive layers",
                "test",
                "test",
                "facts",
                null,
                List.of(),
                null,
                null,           // no summary
                List.of(),      // empty key_points
                null,           // no insight
                null,           // no actionability
                "committed",
                BASE_TIME
        );

        Map<String, Object> drawer = readToolService.getDrawer(WRITER,
                UUID.fromString((String) created.get("id")));
        assertThat(drawer).isNotNull();
        assertThat(drawer.get("content")).isEqualTo("Minimal drawer without progressive layers");
        assertThat(drawer.get("summary")).isNull();
        assertThat((List<?>) drawer.get("key_points")).isEmpty();
        assertThat(drawer.get("insight")).isNull();
        assertThat(drawer.get("actionability")).isNull();
    }

    // -----------------------------------------------------------------------
    // 3. Actionability constraint — valid values
    //
    // NOTE: The Java migration (V0002) defines actionability as TEXT with
    // no CHECK constraint. The Python schema enforces
    //   CHECK (actionability IN ('actionable','reference','someday','archive')).
    // Until a migration adds the constraint on the Java track, any string is
    // accepted. These tests document what SHOULD be valid values and verify
    // they round-trip correctly.
    // -----------------------------------------------------------------------

    @Test
    void actionabilityActionableIsAccepted() {
        Map<String, Object> created = writeToolService.addDrawer(
                WRITER,
                "Actionable drawer",
                "test", "test", "facts",
                null, List.of(), null, "summary", List.of(), null,
                "actionable",
                "committed", BASE_TIME
        );
        Map<String, Object> drawer = readToolService.getDrawer(WRITER,
                UUID.fromString((String) created.get("id")));
        assertThat(drawer.get("actionability")).isEqualTo("actionable");
    }

    @Test
    void actionabilityReferenceIsAccepted() {
        Map<String, Object> created = writeToolService.addDrawer(
                WRITER,
                "Reference drawer",
                "test", "test", "facts",
                null, List.of(), null, "summary", List.of(), null,
                "reference",
                "committed", BASE_TIME
        );
        Map<String, Object> drawer = readToolService.getDrawer(WRITER,
                UUID.fromString((String) created.get("id")));
        assertThat(drawer.get("actionability")).isEqualTo("reference");
    }

    @Test
    void actionabilitySomedayIsAccepted() {
        Map<String, Object> created = writeToolService.addDrawer(
                WRITER,
                "Someday drawer",
                "test", "test", "facts",
                null, List.of(), null, "summary", List.of(), null,
                "someday",
                "committed", BASE_TIME
        );
        Map<String, Object> drawer = readToolService.getDrawer(WRITER,
                UUID.fromString((String) created.get("id")));
        assertThat(drawer.get("actionability")).isEqualTo("someday");
    }

    @Test
    void actionabilityArchiveIsAccepted() {
        Map<String, Object> created = writeToolService.addDrawer(
                WRITER,
                "Archive drawer",
                "test", "test", "facts",
                null, List.of(), null, "summary", List.of(), null,
                "archive",
                "committed", BASE_TIME
        );
        Map<String, Object> drawer = readToolService.getDrawer(WRITER,
                UUID.fromString((String) created.get("id")));
        assertThat(drawer.get("actionability")).isEqualTo("archive");
    }

    /**
     * Enforced by migration V0007__actionability_check.sql which adds
     * CHECK (actionability IN ('actionable','reference','someday','archive')).
     * Matches the Python schema (migrations/0001_initial.sql).
     */
    @Test
    void invalidActionabilityIsRejectedByCheckConstraint() {
        assertThatThrownBy(() -> writeToolService.addDrawer(
                WRITER,
                "Bad actionability drawer",
                "test", "test", "facts",
                null, List.of(), null, "summary", List.of(), null,
                "invalid_value",
                "committed", BASE_TIME
        )).hasMessageContaining("drawers_actionability_check");
    }

    @Test
    void actionabilityNullIsAccepted() {
        Map<String, Object> created = writeToolService.addDrawer(
                WRITER,
                "Null actionability drawer",
                "test", "test", "facts",
                null, List.of(), null, "summary", List.of(), null,
                null,
                "committed", BASE_TIME
        );
        Map<String, Object> drawer = readToolService.getDrawer(WRITER,
                UUID.fromString((String) created.get("id")));
        assertThat(drawer.get("actionability")).isNull();
    }

    // -----------------------------------------------------------------------
    // 4. Duplicate checking with threshold sensitivity
    //
    // FixedEmbeddingClient maps "duplicate oracle" to (0.9, 0.9, 0.0, ...).
    // Two "duplicate oracle" texts produce identical vectors -> cosine
    // similarity = 1.0. A low threshold catches them; a high (>1.0) does not.
    // -----------------------------------------------------------------------

    @Test
    void checkDuplicateLowThresholdFindsMoreMatches() {
        writeToolService.addDrawer(
                WRITER,
                "Duplicate oracle alpha",
                "test", "dup", "facts",
                null, List.of(), null,
                "Duplicate oracle alpha",
                List.of(), null, null,
                "committed", BASE_TIME
        );

        // Same "duplicate oracle" prefix -> identical embedding -> similarity ~1.0
        List<Map<String, Object>> dupes = writeToolService.checkDuplicate(
                "Duplicate oracle beta", 0.5);
        assertThat(dupes).isNotEmpty();
        assertThat((Double) dupes.get(0).get("similarity")).isGreaterThan(0.5);
    }

    @Test
    void checkDuplicateHighThresholdReturnsFewerMatches() {
        writeToolService.addDrawer(
                WRITER,
                "Duplicate oracle alpha",
                "test", "dup", "facts",
                null, List.of(), null,
                "Duplicate oracle alpha",
                List.of(), null, null,
                "committed", BASE_TIME
        );

        // FixedEmbeddingClient: "duplicate oracle alpha" and "duplicate oracle beta"
        // both map to the same vector (0.9, 0.9, 0.0, ...) -> similarity 1.0.
        // A threshold of 0.99 should still match (similarity rounds to 1.0).
        List<Map<String, Object>> highThreshold = writeToolService.checkDuplicate(
                "Duplicate oracle beta", 0.99);
        assertThat(highThreshold).isNotEmpty();

        // Completely different content should not match at a strict threshold.
        // Note: FixedEmbeddingClient is word-hash based, so unrelated content still has
        // low-but-nonzero cosine similarity (~0.56). A strict threshold (0.9) correctly
        // excludes it.
        List<Map<String, Object>> noMatch = writeToolService.checkDuplicate(
                "Cooking Italian pasta recipes for dinner tonight", 0.9);
        assertThat(noMatch).isEmpty();
    }

    @Test
    void checkDuplicateNoMatchForDifferentContent() {
        writeToolService.addDrawer(
                WRITER,
                "PostgreSQL vector search with pgvector",
                "eng", "db", "facts",
                null, List.of(), null, "pgvector search",
                List.of(), null, null,
                "committed", BASE_TIME
        );

        List<Map<String, Object>> dupes = writeToolService.checkDuplicate(
                "Cooking Italian pasta recipes for dinner tonight", 0.9);
        assertThat(dupes).isEmpty();
    }

    // -----------------------------------------------------------------------
    // 5. Revise drawer preserves L0/L1/L2/L3 when only updating content
    // -----------------------------------------------------------------------

    @Test
    void reviseDrawerPreservesAllLayersWhenOnlyContentChanges() {
        Map<String, Object> original = writeToolService.addDrawer(
                WRITER,
                "Original content about auth migration",
                "eng", "auth", "facts",
                "system",
                List.of("auth", "migration"),
                1,
                "Auth migration v1",
                List.of("Migrate from Camunda", "Use Temporal", "Q3 deadline"),
                "This unblocks the Go rewrite",
                "actionable",
                "committed",
                BASE_TIME
        );
        UUID originalId = UUID.fromString((String) original.get("id"));

        Map<String, Object> revision = writeToolService.reviseDrawer(
                WRITER, originalId, "Updated content about auth migration complete", null);

        UUID newId = UUID.fromString((String) revision.get("new_id"));
        Map<String, Object> revised = readToolService.getDrawer(WRITER,newId);

        assertThat(revised).isNotNull();
        // L0 updated
        assertThat(revised.get("content")).isEqualTo("Updated content about auth migration complete");
        // L1 preserved (newSummary was null)
        assertThat(revised.get("summary")).isEqualTo("Auth migration v1");
        // L2 preserved
        assertThat((List<String>) revised.get("key_points"))
                .containsExactly("Migrate from Camunda", "Use Temporal", "Q3 deadline");
        // L3 preserved
        assertThat(revised.get("insight")).isEqualTo("This unblocks the Go rewrite");
        // Actionability preserved
        assertThat(revised.get("actionability")).isEqualTo("actionable");
        // Importance preserved
        assertThat(revised.get("importance")).isEqualTo(1);
    }

    @Test
    void reviseDrawerUpdatesL1SummaryWhilePreservingL2L3() {
        Map<String, Object> original = writeToolService.addDrawer(
                WRITER,
                "Content about database optimization",
                "eng", "db", "facts",
                "system",
                List.of("db"),
                2,
                "DB optimization v1",
                List.of("Indexing strategy", "Query tuning"),
                "HNSW index is the bottleneck",
                "reference",
                "committed",
                BASE_TIME
        );
        UUID originalId = UUID.fromString((String) original.get("id"));

        Map<String, Object> revision = writeToolService.reviseDrawer(
                WRITER, originalId, "Revised DB optimization content", "DB optimization v2");

        UUID newId = UUID.fromString((String) revision.get("new_id"));
        Map<String, Object> revised = readToolService.getDrawer(WRITER,newId);

        assertThat(revised).isNotNull();
        assertThat(revised.get("content")).isEqualTo("Revised DB optimization content");
        assertThat(revised.get("summary")).isEqualTo("DB optimization v2");
        // L2 and L3 carried over from original
        assertThat((List<String>) revised.get("key_points"))
                .containsExactly("Indexing strategy", "Query tuning");
        assertThat(revised.get("insight")).isEqualTo("HNSW index is the bottleneck");
        assertThat(revised.get("actionability")).isEqualTo("reference");
    }

    @Test
    void reviseDrawerCreatesNewRowAndClosesOld() {
        Map<String, Object> original = writeToolService.addDrawer(
                WRITER,
                "Version 1 content",
                "eng", "docs", "facts",
                "system",
                List.of(),
                3,
                "Summary v1",
                List.of("point-a"),
                "insight-v1",
                "someday",
                "committed",
                BASE_TIME
        );
        UUID originalId = UUID.fromString((String) original.get("id"));

        Map<String, Object> revision = writeToolService.reviseDrawer(
                WRITER, originalId, "Version 2 content", null);

        UUID newId = UUID.fromString((String) revision.get("new_id"));
        assertThat(newId).isNotEqualTo(originalId);

        // Old row is closed (valid_until is set)
        Map<String, Object> oldDrawer = readToolService.getDrawer(WRITER,originalId);
        assertThat(oldDrawer).isNotNull();
        assertThat(oldDrawer.get("valid_until")).isNotNull();

        // New row points to old via parent_id
        Map<String, Object> newDrawer = readToolService.getDrawer(WRITER,newId);
        assertThat(newDrawer).isNotNull();
        assertThat(newDrawer.get("parent_id")).isEqualTo(originalId.toString());
        assertThat(newDrawer.get("valid_until")).isNull();
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Test
    void drawerWithEmptyKeyPointsAndNullInsightRoundTrips() {
        Map<String, Object> created = writeToolService.addDrawer(
                WRITER,
                "Edge case drawer with empty arrays",
                "test", "edge", "facts",
                null, List.of(), null,
                "Has a summary",
                List.of(),    // empty key_points
                null,         // null insight
                null,         // null actionability
                "committed",
                BASE_TIME
        );

        Map<String, Object> drawer = readToolService.getDrawer(WRITER,
                UUID.fromString((String) created.get("id")));
        assertThat(drawer.get("summary")).isEqualTo("Has a summary");
        assertThat((List<?>) drawer.get("key_points")).isEmpty();
        assertThat(drawer.get("insight")).isNull();
        assertThat(drawer.get("actionability")).isNull();
    }

    @Test
    void multipleDrawersWithDifferentLayerCombinations() {
        // L0 only
        Map<String, Object> l0Only = writeToolService.addDrawer(
                WRITER, "L0 only content", "test", "layers", "facts",
                null, List.of(), null, null, List.of(), null, null,
                "committed", BASE_TIME);

        // L0 + L1
        Map<String, Object> l0l1 = writeToolService.addDrawer(
                WRITER, "L0 plus L1 content", "test", "layers", "facts",
                null, List.of(), null, "Has summary only", List.of(), null, null,
                "committed", BASE_TIME.plusSeconds(1));

        // L0 + L1 + L2
        Map<String, Object> l0l1l2 = writeToolService.addDrawer(
                WRITER, "L0 plus L1 plus L2 content", "test", "layers", "facts",
                null, List.of(), null, "Summary present",
                List.of("point-1", "point-2"), null, null,
                "committed", BASE_TIME.plusSeconds(2));

        // L0 + L1 + L2 + L3 (all layers)
        Map<String, Object> allLayers = writeToolService.addDrawer(
                WRITER, "All layers present", "test", "layers", "facts",
                null, List.of(), null, "Full summary",
                List.of("key-a", "key-b", "key-c"), "Deep insight", "actionable",
                "committed", BASE_TIME.plusSeconds(3));

        // Verify each round-trips correctly
        Map<String, Object> d0 = readToolService.getDrawer(WRITER,UUID.fromString((String) l0Only.get("id")));
        assertThat(d0.get("summary")).isNull();
        assertThat((List<?>) d0.get("key_points")).isEmpty();
        assertThat(d0.get("insight")).isNull();

        Map<String, Object> d1 = readToolService.getDrawer(WRITER,UUID.fromString((String) l0l1.get("id")));
        assertThat(d1.get("summary")).isEqualTo("Has summary only");
        assertThat((List<?>) d1.get("key_points")).isEmpty();
        assertThat(d1.get("insight")).isNull();

        Map<String, Object> d2 = readToolService.getDrawer(WRITER,UUID.fromString((String) l0l1l2.get("id")));
        assertThat(d2.get("summary")).isEqualTo("Summary present");
        assertThat((List<String>) d2.get("key_points")).containsExactly("point-1", "point-2");
        assertThat(d2.get("insight")).isNull();

        Map<String, Object> d3 = readToolService.getDrawer(WRITER,UUID.fromString((String) allLayers.get("id")));
        assertThat(d3.get("summary")).isEqualTo("Full summary");
        assertThat((List<String>) d3.get("key_points")).containsExactly("key-a", "key-b", "key-c");
        assertThat(d3.get("insight")).isEqualTo("Deep insight");
        assertThat(d3.get("actionability")).isEqualTo("actionable");
    }

    // -----------------------------------------------------------------------
    // Test application and config
    // -----------------------------------------------------------------------

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            WriteToolService.class,
            WriteToolRepository.class,
            ReadToolService.class,
            DrawerReadRepository.class,
            DrawerSearchRepository.class,
            KgSearchRepository.class,
            AdminToolRepository.class,
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

        @Bean
        AdminToolService adminToolService(AdminToolRepository adminToolRepository) {
            // EmbeddingMigrationService is not loaded in this slim context;
            // provide AdminToolService with a no-op migration stub since
            // ReadToolService needs it only to call logAccess.
            return new AdminToolService(adminToolRepository, new com.hivemem.embedding.EmbeddingMigrationService(
                    new FixedEmbeddingClient(),
                    null
            ) {
                @Override
                public void run(org.springframework.boot.ApplicationArguments args) {
                    // no-op: skip migration in unit test context
                }
            });
        }
    }
}
