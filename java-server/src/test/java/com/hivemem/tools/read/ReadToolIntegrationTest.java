package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.auth.RateLimiter;
import com.hivemem.auth.TokenService;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.write.WriteToolService;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ReadToolIntegrationTest.TestConfig.class)
@Testcontainers
class ReadToolIntegrationTest {

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
    private RateLimiter rateLimiter;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReadToolService readToolService;

    @Autowired
    private WriteToolService writeToolService;

    @BeforeEach
    void resetDatabase() {
        rateLimiter.clearAll();
        dslContext.execute("TRUNCATE TABLE agent_diary, drawer_references, references_, blueprints, identity, agents, facts, tunnels, drawers CASCADE");
    }

    @Test
    void toolsListExposesImplementedReadHandlers() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools[0].name").value("hivemem_status"))
                .andExpect(jsonPath("$.result.tools[1].name").value("hivemem_search"))
                .andExpect(jsonPath("$.result.tools[2].name").value("hivemem_search_kg"))
                .andExpect(jsonPath("$.result.tools[3].name").value("hivemem_get_drawer"))
                .andExpect(jsonPath("$.result.tools[4].name").value("hivemem_list_wings"))
                .andExpect(jsonPath("$.result.tools[5].name").value("hivemem_list_halls"))
                .andExpect(jsonPath("$.result.tools[6].name").value("hivemem_traverse"))
                .andExpect(jsonPath("$.result.tools[7].name").value("hivemem_quick_facts"))
                .andExpect(jsonPath("$.result.tools[8].name").value("hivemem_time_machine"))
                .andExpect(jsonPath("$.result.tools[9].name").value("hivemem_drawer_history"))
                .andExpect(jsonPath("$.result.tools[10].name").value("hivemem_fact_history"))
                .andExpect(jsonPath("$.result.tools[11].name").value("hivemem_pending_approvals"))
                .andExpect(jsonPath("$.result.tools[12].name").value("hivemem_reading_list"))
                .andExpect(jsonPath("$.result.tools[13].name").value("hivemem_list_agents"))
                .andExpect(jsonPath("$.result.tools[14].name").value("hivemem_diary_read"))
                .andExpect(jsonPath("$.result.tools[15].name").value("hivemem_get_blueprint"))
                .andExpect(jsonPath("$.result.tools[16].name").value("hivemem_wake_up"));
    }

    @Test
    void statusToolReturnsCountsAndWingsFromSql() throws Exception {
        seedStatusRows();

        JsonNode content = callToolContent("hivemem_status", Map.of());
        assertThat(content.path("drawers").asInt()).isEqualTo(2);
        assertThat(content.path("facts").asInt()).isEqualTo(2);
        assertThat(content.path("tunnels").asInt()).isEqualTo(1);
        assertThat(content.path("pending").asInt()).isEqualTo(3);
        assertThat(content.path("last_activity").asText()).isEqualTo("2026-04-03T12:00:00Z");
        assertThat(content.path("wings").get(0).asText()).isEqualTo("alpha");
        assertThat(content.path("wings").get(1).asText()).isEqualTo("beta");
    }

    @Test
    void searchToolReturnsRankedDrawerResults() throws Exception {
        insertDrawer(
                UUID.fromString("00000000-0000-0000-0000-000000000501"),
                null,
                "Semantic oracle drawer",
                "alpha",
                "search",
                "facts",
                "system",
                1,
                "Semantic oracle summary",
                null,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-03T10:00:00Z"),
                OffsetDateTime.parse("2026-04-03T10:00:00Z"),
                null
        );
        insertDrawer(
                UUID.fromString("00000000-0000-0000-0000-000000000502"),
                null,
                "Keyword oracle drawer",
                "alpha",
                "search",
                "facts",
                "system",
                5,
                "Keyword oracle summary",
                null,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-03T10:05:00Z"),
                OffsetDateTime.parse("2026-04-03T10:05:00Z"),
                null
        );

        JsonNode results = callToolContent("hivemem_search", Map.of("query", "semantic oracle", "limit", 10));
        assertThat(results.get(0).path("id").asText()).isEqualTo("00000000-0000-0000-0000-000000000501");
        assertThat(results.get(0).path("score_total").isNumber()).isTrue();
        assertThat(results.get(0).path("score_semantic").isNumber()).isTrue();
        assertThat(results.get(0).path("score_keyword").isNumber()).isTrue();
        assertThat(results).hasSize(2);

        JsonNode weightedResults = callToolContent("hivemem_search", Map.of(
                "query", "semantic oracle",
                "limit", 10,
                "weight_semantic", 0.05,
                "weight_keyword", 0.05,
                "weight_recency", 0.05,
                "weight_importance", 0.75,
                "weight_popularity", 0.1
        ));
        assertThat(weightedResults.get(0).path("id").asText()).isEqualTo("00000000-0000-0000-0000-000000000501");
    }

    @Test
    void searchKgToolReturnsCommittedFactsOnly() throws Exception {
        seedStatusRows();

        JsonNode content = callToolContent("hivemem_search_kg", Map.of("subject", "HiveMem", "limit", 10));
        assertThat(content.get(0).path("subject").asText()).isEqualTo("HiveMem");
        assertThat(content.get(0).path("predicate").asText()).isEqualTo("runs on");
        assertThat(content.get(0).path("object").asText()).isEqualTo("PostgreSQL");
        assertThat(content.get(1).path("object").asText()).isEqualTo("Java");
        assertThat(content).hasSize(2);
    }

    @Test
    void searchKgToolRejectsInvalidLimit() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":31,
                                  "method":"tools/call",
                                  "params":{
                                    "name":"hivemem_search_kg",
                                    "arguments":{"subject":"HiveMem","limit":1000}
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("Invalid limit"));
    }

    @Test
    void getDrawerToolReturnsStoredDrawerPayload() throws Exception {
        UUID drawerId = UUID.fromString("00000000-0000-0000-0000-000000000111");
        insertDrawer(
                drawerId,
                null,
                "The JVM migration plan",
                "alpha",
                "planning",
                "facts",
                "system",
                4,
                "Java migration slice",
                "reference",
                "archive",
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-03T12:00:00Z"),
                OffsetDateTime.parse("2026-04-03T12:00:00Z"),
                null
        );

        JsonNode content = callToolContent("hivemem_get_drawer", Map.of("drawer_id", "00000000-0000-0000-0000-000000000111"));
        assertThat(content.path("id").asText()).isEqualTo("00000000-0000-0000-0000-000000000111");
        assertThat(content.path("parent_id").isNull()).isTrue();
        assertThat(content.path("content").asText()).isEqualTo("The JVM migration plan");
        assertThat(content.path("tags").isArray()).isTrue();
        assertThat(content.path("tags")).isEmpty();
        assertThat(content.path("key_points").isArray()).isTrue();
        assertThat(content.path("key_points")).isEmpty();
        assertThat(content.path("created_by").asText()).isEqualTo("writer");
    }

    @Test
    void getDrawerToolReturnsNullWhenDrawerDoesNotExist() throws Exception {
        JsonNode content = callToolContent("hivemem_get_drawer", Map.of("drawer_id", "00000000-0000-0000-0000-000000000999"));
        assertThat(content.isNull()).isTrue();
    }

    @Test
    void getDrawerLogsAccessAutomatically() {
        UUID drawerId = UUID.fromString(
            (String) writeToolService.addDrawer(
                new AuthPrincipal("fixture-writer", AuthRole.WRITER),
                "test drawer for auto-log",
                "testing", "autolog", "base",
                null, null, null, null, null, null, null, null, null
            ).get("id"));

        long beforeCount = dslContext.fetchCount(
            DSL.table("access_log"),
            DSL.field("drawer_id").eq(drawerId)
        );

        readToolService.getDrawer(
            new AuthPrincipal("test-writer", AuthRole.WRITER),
            drawerId
        );

        long afterCount = dslContext.fetchCount(
            DSL.table("access_log"),
            DSL.field("drawer_id").eq(drawerId)
        );
        org.junit.jupiter.api.Assertions.assertEquals(beforeCount + 1, afterCount);

        String accessedBy = dslContext
            .select(DSL.field("accessed_by", String.class))
            .from("access_log")
            .where(DSL.field("drawer_id").eq(drawerId))
            .orderBy(DSL.field("accessed_at").desc())
            .limit(1)
            .fetchOne(DSL.field("accessed_by", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("test-writer", accessedBy);
    }

    @Test
    void listWingsToolReturnsWingAndCountStats() throws Exception {
        seedStatusRows();
        insertDrawer(
                UUID.fromString("00000000-0000-0000-0000-000000000004"),
                null,
                "Alpha strategy drawer",
                "alpha",
                "strategy",
                "notes",
                "system",
                2,
                "Strategy summary",
                null,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-03T11:00:00Z"),
                OffsetDateTime.parse("2026-04-03T11:00:00Z"),
                null
        );

        JsonNode content = callToolContent("hivemem_list_wings", Map.of());
        assertThat(content.get(0).path("wing").asText()).isEqualTo("alpha");
        assertThat(content.get(0).path("hall_count").asInt()).isEqualTo(2);
        assertThat(content.get(0).path("drawer_count").asInt()).isEqualTo(2);
        assertThat(content.get(1).path("wing").asText()).isEqualTo("beta");
        assertThat(content).hasSize(2);
    }

    @Test
    void listHallsToolReturnsHallsForWing() throws Exception {
        seedStatusRows();
        insertDrawer(
                UUID.fromString("00000000-0000-0000-0000-000000000004"),
                null,
                "Alpha strategy drawer",
                "alpha",
                "strategy",
                "notes",
                "system",
                2,
                "Strategy summary",
                null,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-03T11:00:00Z"),
                OffsetDateTime.parse("2026-04-03T11:00:00Z"),
                null
        );

        JsonNode content = callToolContent("hivemem_list_halls", Map.of("wing", "alpha"));
        assertThat(content.get(0).path("hall").asText()).isEqualTo("planning");
        assertThat(content.get(0).path("drawer_count").asInt()).isEqualTo(1);
        assertThat(content.get(1).path("hall").asText()).isEqualTo("strategy");
        assertThat(content.get(1).path("drawer_count").asInt()).isEqualTo(1);
    }

    @Test
    void traverseToolReturnsBidirectionalDepthLimitedEdges() throws Exception {
        seedStatusRows();
        UUID drawerThree = UUID.fromString("00000000-0000-0000-0000-000000000004");
        insertDrawer(
                drawerThree,
                null,
                "Third committed drawer",
                "alpha",
                "ops",
                "notes",
                "system",
                1,
                "Ops summary",
                null,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-04T10:00:00Z"),
                OffsetDateTime.parse("2026-04-04T10:00:00Z"),
                null
        );
        insertTunnel(
                UUID.fromString("00000000-0000-0000-0000-000000000203"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                drawerThree,
                "related_to",
                "Second to third link",
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-04T11:00:00Z"),
                OffsetDateTime.parse("2026-04-04T11:00:00Z"),
                null
        );

        JsonNode content = callToolContent("hivemem_traverse", Map.of("drawer_id", "00000000-0000-0000-0000-000000000002", "max_depth", 1));
        assertThat(content.get(0).path("from_drawer").asText()).isEqualTo("00000000-0000-0000-0000-000000000001");
        assertThat(content.get(0).path("to_drawer").asText()).isEqualTo("00000000-0000-0000-0000-000000000002");
        assertThat(content.get(0).path("relation").asText()).isEqualTo("related_to");
        assertThat(content.get(0).path("depth").asInt()).isEqualTo(1);
        assertThat(content.get(1).path("from_drawer").asText()).isEqualTo("00000000-0000-0000-0000-000000000002");
        assertThat(content.get(1).path("to_drawer").asText()).isEqualTo("00000000-0000-0000-0000-000000000004");
        assertThat(content).hasSize(2);
    }

    @Test
    void quickFactsToolReturnsSubjectAndObjectMatches() throws Exception {
        seedStatusRows();
        insertFact(
                UUID.fromString("00000000-0000-0000-0000-000000000104"),
                null,
                "Viktor",
                "created",
                "HiveMem",
                0.88f,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-06T12:00:00Z"),
                OffsetDateTime.parse("2026-04-06T12:00:00Z"),
                null
        );

        JsonNode content = callToolContent("hivemem_quick_facts", Map.of("entity", "HiveMem"));
        assertThat(content).hasSize(3);
        assertThat(content.get(0).path("subject").asText()).isEqualTo("Viktor");
        assertThat(content.get(0).path("object").asText()).isEqualTo("HiveMem");
        assertThat(content.get(1).path("object").asText()).isEqualTo("PostgreSQL");
        assertThat(content.get(2).path("object").asText()).isEqualTo("Java");
    }

    @Test
    void timeMachineToolReturnsCurrentAndHistoricalSnapshots() throws Exception {
        seedAliceHistoryRows();

        JsonNode current = callToolContent("hivemem_time_machine", Map.of("subject", "Alice", "limit", 10));
        assertThat(current).hasSize(2);
        assertThat(current.get(0).path("object").asText()).isEqualTo("New City");
        assertThat(current.get(1).path("object").asText()).isEqualTo("Acme");

        JsonNode historical = callToolContent("hivemem_time_machine", Map.of(
                "subject", "Alice",
                "as_of", "2025-09-01T00:00:00Z",
                "limit", 10
        ));
        assertThat(historical).hasSize(2);
        assertThat(historical.get(0).path("object").asText()).isEqualTo("Old City");
        assertThat(historical.get(1).path("object").asText()).isEqualTo("Acme");
    }

    @Test
    void drawerHistoryToolReturnsOldestVersionFirst() throws Exception {
        UUID originalId = UUID.fromString("00000000-0000-0000-0000-000000000301");
        UUID revisedId = UUID.fromString("00000000-0000-0000-0000-000000000302");
        insertDrawer(
                originalId,
                null,
                "Original drawer content",
                "alpha",
                "history",
                "facts",
                "system",
                3,
                "Drawer V1",
                null,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2025-01-01T00:00:00Z"),
                OffsetDateTime.parse("2025-01-01T00:00:00Z"),
                null
        );
        insertDrawer(
                revisedId,
                originalId,
                "Revised drawer content",
                "alpha",
                "history",
                "facts",
                "system",
                3,
                "Drawer V2",
                null,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2025-02-01T00:00:00Z"),
                OffsetDateTime.parse("2025-02-01T00:00:00Z"),
                null
        );

        JsonNode content = callToolContent("hivemem_drawer_history", Map.of("drawer_id", "00000000-0000-0000-0000-000000000302"));
        assertThat(content).hasSize(2);
        assertThat(content.get(0).path("summary").asText()).isEqualTo("Drawer V1");
        assertThat(content.get(1).path("summary").asText()).isEqualTo("Drawer V2");
        assertThat(content.get(0).path("parent_id").isNull()).isTrue();
        assertThat(content.get(1).path("parent_id").asText()).isEqualTo("00000000-0000-0000-0000-000000000301");
    }

    @Test
    void factHistoryToolReturnsOldestVersionFirst() throws Exception {
        UUID originalId = UUID.fromString("00000000-0000-0000-0000-000000000401");
        UUID revisedId = UUID.fromString("00000000-0000-0000-0000-000000000402");
        insertFact(
                originalId,
                null,
                "BOGIS",
                "uses",
                "Camunda7",
                1.0f,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2025-01-01T00:00:00Z"),
                OffsetDateTime.parse("2025-01-01T00:00:00Z"),
                null
        );
        insertFact(
                revisedId,
                originalId,
                "BOGIS",
                "uses",
                "Temporal",
                1.0f,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2025-02-01T00:00:00Z"),
                OffsetDateTime.parse("2025-02-01T00:00:00Z"),
                null
        );

        JsonNode content = callToolContent("hivemem_fact_history", Map.of("fact_id", "00000000-0000-0000-0000-000000000402"));
        assertThat(content).hasSize(2);
        assertThat(content.get(0).path("object").asText()).isEqualTo("Camunda7");
        assertThat(content.get(1).path("object").asText()).isEqualTo("Temporal");
        assertThat(content.get(0).path("parent_id").isNull()).isTrue();
        assertThat(content.get(1).path("parent_id").asText()).isEqualTo("00000000-0000-0000-0000-000000000401");
    }

    @Test
    void pendingApprovalsToolReturnsPendingRowsFromView() throws Exception {
        seedStatusRows();

        JsonNode content = callToolContent("hivemem_pending_approvals", Map.of());
        assertThat(content).hasSize(3);
        assertThat(content.get(0).path("type").asText()).isEqualTo("drawer");
        assertThat(content.get(0).path("description").asText()).isEqualTo("Pending summary");
        assertThat(content.get(1).path("type").asText()).isEqualTo("fact");
        assertThat(content.get(1).path("description").asText()).isEqualTo("HiveMem -> runs on -> Python");
        assertThat(content.get(2).path("type").asText()).isEqualTo("tunnel");
        assertThat(content.get(2).path("description").asText()).isEqualTo("00000000-0000-0000-0000-000000000002 -[refines]-> 00000000-0000-0000-0000-000000000001");
    }

    @Test
    void readingListToolReturnsUnreadAndReadingReferencesWithLinkedDrawerCounts() throws Exception {
        seedStatusRows();
        UUID unreadReference = UUID.fromString("00000000-0000-0000-0000-000000000601");
        UUID readingReference = UUID.fromString("00000000-0000-0000-0000-000000000602");
        UUID archivedReference = UUID.fromString("00000000-0000-0000-0000-000000000603");
        insertReference(
                unreadReference,
                "PostgreSQL guide",
                "https://example.com/postgres",
                "Ada",
                "book",
                "unread",
                null,
                new String[]{"db"},
                1,
                OffsetDateTime.parse("2026-04-06T10:00:00Z")
        );
        insertReference(
                readingReference,
                "Java migration notes",
                "https://example.com/java",
                "Bob",
                "article",
                "reading",
                null,
                new String[]{"java"},
                3,
                OffsetDateTime.parse("2026-04-06T11:00:00Z")
        );
        insertReference(
                archivedReference,
                "Archived note",
                null,
                null,
                "article",
                "archived",
                null,
                null,
                5,
                OffsetDateTime.parse("2026-04-06T12:00:00Z")
        );
        linkReference(
                UUID.fromString("00000000-0000-0000-0000-000000000701"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                unreadReference,
                "source",
                OffsetDateTime.parse("2026-04-06T13:00:00Z")
        );
        linkReference(
                UUID.fromString("00000000-0000-0000-0000-000000000702"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                readingReference,
                "extends",
                OffsetDateTime.parse("2026-04-06T13:30:00Z")
        );
        linkReference(
                UUID.fromString("00000000-0000-0000-0000-000000000703"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                readingReference,
                "source",
                OffsetDateTime.parse("2026-04-06T13:40:00Z")
        );

        JsonNode content = callToolContent("hivemem_reading_list", Map.of());
        assertThat(content).hasSize(2);
        assertThat(content.get(0).path("title").asText()).isEqualTo("PostgreSQL guide");
        assertThat(content.get(0).path("linked_drawers").asInt()).isEqualTo(1);
        assertThat(content.get(0).path("status").asText()).isEqualTo("unread");
        assertThat(content.get(1).path("title").asText()).isEqualTo("Java migration notes");
        assertThat(content.get(1).path("linked_drawers").asInt()).isEqualTo(2);
        assertThat(content.get(1).path("status").asText()).isEqualTo("reading");

        JsonNode filtered = callToolContent("hivemem_reading_list", Map.of("ref_type", "article"));
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).path("title").asText()).isEqualTo("Java migration notes");
        assertThat(filtered.get(0).path("ref_type").asText()).isEqualTo("article");
    }

    @Test
    void listAgentsDiaryReadGetBlueprintAndWakeUpReturnRowsFromSql() throws Exception {
        insertAgent(
                "alpha-agent",
                "Migrate the Java server",
                "daily",
                OffsetDateTime.parse("2026-04-07T09:00:00Z")
        );
        insertAgent(
                "beta-agent",
                "Keep docs updated",
                null,
                OffsetDateTime.parse("2026-04-07T10:00:00Z")
        );
        insertDiaryEntry(
                UUID.fromString("00000000-0000-0000-0000-000000000801"),
                "alpha-agent",
                "First diary entry",
                OffsetDateTime.parse("2026-04-07T11:00:00Z")
        );
        insertDiaryEntry(
                UUID.fromString("00000000-0000-0000-0000-000000000802"),
                "alpha-agent",
                "Second diary entry",
                OffsetDateTime.parse("2026-04-07T12:00:00Z")
        );
        UUID blueprintOne = UUID.fromString("00000000-0000-0000-0000-000000000901");
        UUID blueprintTwo = UUID.fromString("00000000-0000-0000-0000-000000000902");
        UUID blueprintThree = UUID.fromString("00000000-0000-0000-0000-000000000903");
        insertBlueprint(
                blueprintOne,
                "alpha",
                "Alpha blueprint",
                "Narrative v1",
                new String[]{"planning", "delivery"},
                new UUID[]{UUID.fromString("00000000-0000-0000-0000-000000000001")},
                "writer",
                OffsetDateTime.parse("2026-04-07T13:00:00Z"),
                OffsetDateTime.parse("2026-04-07T13:00:00Z"),
                null
        );
        insertBlueprint(
                blueprintTwo,
                "alpha",
                "Alpha blueprint",
                "Narrative v2",
                new String[]{"planning", "delivery", "archive"},
                new UUID[]{UUID.fromString("00000000-0000-0000-0000-000000000002")},
                "writer",
                OffsetDateTime.parse("2026-04-08T13:00:00Z"),
                OffsetDateTime.parse("2026-04-08T13:00:00Z"),
                null
        );
        insertBlueprint(
                blueprintThree,
                "beta",
                "Beta blueprint",
                "Narrative beta",
                new String[]{"delivery"},
                new UUID[]{UUID.fromString("00000000-0000-0000-0000-000000000002")},
                "writer",
                OffsetDateTime.parse("2026-04-08T14:00:00Z"),
                OffsetDateTime.parse("2026-04-08T14:00:00Z"),
                null
        );
        insertIdentity("l0_identity", "You are Alice.", 3, OffsetDateTime.parse("2026-04-07T14:00:00Z"));
        insertIdentity("l1_critical", "Remember the migration plan.", 4, OffsetDateTime.parse("2026-04-07T14:05:00Z"));

        JsonNode agents = callToolContent("hivemem_list_agents", Map.of());
        assertThat(agents).hasSize(2);
        assertThat(agents.get(0).path("name").asText()).isEqualTo("alpha-agent");
        assertThat(agents.get(1).path("name").asText()).isEqualTo("beta-agent");

        JsonNode diary = callToolContent("hivemem_diary_read", Map.of("agent", "alpha-agent"));
        assertThat(diary).hasSize(2);
        assertThat(diary.get(0).path("entry").asText()).isEqualTo("Second diary entry");
        assertThat(diary.get(1).path("entry").asText()).isEqualTo("First diary entry");

        JsonNode diaryLimited = callToolContent("hivemem_diary_read", Map.of("agent", "alpha-agent", "last_n", 1));
        assertThat(diaryLimited).hasSize(1);
        assertThat(diaryLimited.get(0).path("entry").asText()).isEqualTo("Second diary entry");

        JsonNode blueprint = callToolContent("hivemem_get_blueprint", Map.of("wing", "alpha"));
        assertThat(blueprint).hasSize(2);
        assertThat(blueprint.get(0).path("id").asText()).isEqualTo(blueprintTwo.toString());
        assertThat(blueprint.get(0).path("hall_order").get(2).asText()).isEqualTo("archive");
        assertThat(blueprint.get(1).path("id").asText()).isEqualTo(blueprintOne.toString());
        assertThat(blueprint.get(1).path("key_drawers").get(0).asText()).isEqualTo("00000000-0000-0000-0000-000000000001");

        JsonNode allBlueprints = callToolContent("hivemem_get_blueprint", Map.of());
        assertThat(allBlueprints).hasSize(3);
        assertThat(allBlueprints.get(0).path("id").asText()).isEqualTo(blueprintTwo.toString());
        assertThat(allBlueprints.get(1).path("id").asText()).isEqualTo(blueprintOne.toString());
        assertThat(allBlueprints.get(2).path("id").asText()).isEqualTo(blueprintThree.toString());
        assertThat(allBlueprints.get(2).path("wing").asText()).isEqualTo("beta");

        JsonNode wakeUp = callToolContent("hivemem_wake_up", Map.of());
        assertThat(wakeUp.path("l0_identity").path("content").asText()).isEqualTo("You are Alice.");
        assertThat(wakeUp.path("l0_identity").path("token_count").asInt()).isEqualTo(3);
        assertThat(wakeUp.path("l1_critical").path("content").asText()).isEqualTo("Remember the migration plan.");
        assertThat(wakeUp.path("l1_critical").path("token_count").asInt()).isEqualTo(4);
    }

    @Test
    void readingListToolRejectsInvalidLimit() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":20,
                                  "method":"tools/call",
                                  "params":{"name":"hivemem_reading_list","arguments":{"limit":0}}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("Invalid limit"));

        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":201,
                                  "method":"tools/call",
                                  "params":{"name":"hivemem_reading_list","arguments":{"limit":101}}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("Invalid limit"));

        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":202,
                                  "method":"tools/call",
                                  "params":{"name":"hivemem_reading_list","arguments":{"limit":1.9}}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("Invalid limit"));
    }

    @Test
    void diaryReadToolRejectsInvalidLimit() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":21,
                                  "method":"tools/call",
                                  "params":{"name":"hivemem_diary_read","arguments":{"agent":"alpha-agent","last_n":101}}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("Invalid last_n"));

        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":211,
                                  "method":"tools/call",
                                  "params":{"name":"hivemem_diary_read","arguments":{"agent":"alpha-agent","last_n":1.5}}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("Invalid last_n"));
    }

    @Test
    void diaryReadToolRejectsMissingAgent() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":22,
                                  "method":"tools/call",
                                  "params":{"name":"hivemem_diary_read","arguments":{}}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("Missing agent"));

        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":221,
                                  "method":"tools/call",
                                  "params":{"name":"hivemem_diary_read","arguments":{"agent":123}}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("Missing agent"));
    }

    private void seedStatusRows() {
        UUID committedDrawer = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondCommittedDrawer = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID pendingDrawer = UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID committedFact = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID secondCommittedFact = UUID.fromString("00000000-0000-0000-0000-000000000103");
        UUID pendingFact = UUID.fromString("00000000-0000-0000-0000-000000000102");
        UUID committedTunnel = UUID.fromString("00000000-0000-0000-0000-000000000201");
        UUID pendingTunnel = UUID.fromString("00000000-0000-0000-0000-000000000202");

        insertDrawer(
                committedDrawer,
                null,
                "First committed drawer",
                "alpha",
                "planning",
                "facts",
                "system",
                3,
                "Alpha summary",
                null,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-01T10:00:00Z"),
                OffsetDateTime.parse("2026-04-01T10:00:00Z"),
                null
        );
        insertDrawer(
                secondCommittedDrawer,
                committedDrawer,
                "Second committed drawer",
                "beta",
                "delivery",
                "events",
                "system",
                2,
                "Beta summary",
                null,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-02T10:00:00Z"),
                OffsetDateTime.parse("2026-04-02T10:00:00Z"),
                null
        );
        insertDrawer(
                pendingDrawer,
                null,
                "Pending drawer",
                "gamma",
                "intake",
                "facts",
                "system",
                1,
                "Pending summary",
                null,
                null,
                "pending",
                "writer",
                OffsetDateTime.parse("2026-04-03T12:00:00Z"),
                OffsetDateTime.parse("2026-04-03T12:00:00Z"),
                null
        );

        insertFact(
                committedFact,
                null,
                "HiveMem",
                "runs on",
                "PostgreSQL",
                0.91f,
                committedDrawer,
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-01T11:00:00Z"),
                OffsetDateTime.parse("2026-04-01T11:00:00Z"),
                null
        );
        insertFact(
                secondCommittedFact,
                null,
                "HiveMem",
                "runs on",
                "Java",
                0.73f,
                secondCommittedDrawer,
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-01T10:30:00Z"),
                OffsetDateTime.parse("2026-04-01T10:30:00Z"),
                null
        );
        insertFact(
                pendingFact,
                null,
                "HiveMem",
                "runs on",
                "Python",
                0.44f,
                pendingDrawer,
                "pending",
                "writer",
                OffsetDateTime.parse("2026-04-04T11:00:00Z"),
                OffsetDateTime.parse("2026-04-04T11:00:00Z"),
                null
        );
        insertTunnel(
                committedTunnel,
                committedDrawer,
                secondCommittedDrawer,
                "related_to",
                "Committed link",
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-02T11:00:00Z"),
                OffsetDateTime.parse("2026-04-02T11:00:00Z"),
                null
        );
        insertTunnel(
                pendingTunnel,
                secondCommittedDrawer,
                committedDrawer,
                "refines",
                "Pending link",
                "pending",
                "writer",
                OffsetDateTime.parse("2026-04-05T11:00:00Z"),
                OffsetDateTime.parse("2026-04-05T11:00:00Z"),
                null
        );
    }

    private void seedAliceHistoryRows() {
        insertFact(
                UUID.fromString("00000000-0000-0000-0000-000000000501"),
                null,
                "Alice",
                "works_at",
                "Acme",
                1.0f,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2025-01-01T00:00:00Z"),
                OffsetDateTime.parse("2025-01-01T00:00:00Z"),
                null
        );
        insertFact(
                UUID.fromString("00000000-0000-0000-0000-000000000502"),
                null,
                "Alice",
                "lives_in",
                "Old City",
                0.9f,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2025-06-01T00:00:00Z"),
                OffsetDateTime.parse("2025-06-01T00:00:00Z"),
                OffsetDateTime.parse("2025-12-31T00:00:00Z")
        );
        insertFact(
                UUID.fromString("00000000-0000-0000-0000-000000000503"),
                null,
                "Alice",
                "lives_in",
                "New City",
                1.0f,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2025-12-31T00:00:00Z"),
                OffsetDateTime.parse("2025-12-31T00:00:00Z"),
                null
        );
    }

    private void insertDrawer(
            UUID id,
            UUID parentId,
            String content,
            String wing,
            String hall,
            String room,
            String source,
            Integer importance,
            String summary,
            String insight,
            String actionability,
            String status,
            String createdBy,
            OffsetDateTime createdAt,
            OffsetDateTime validFrom,
            OffsetDateTime validUntil
    ) {
        dslContext.execute(
                """
                INSERT INTO drawers (
                    id, parent_id, content, wing, hall, room, source, importance, summary,
                    insight, actionability, status, created_by, created_at, valid_from, valid_until
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?::timestamptz)
                """,
                id, parentId, content, wing, hall, room, source, importance, summary,
                insight, actionability, status, createdBy, createdAt, validFrom, validUntil
        );
    }

    private void insertFact(
            UUID id,
            UUID parentId,
            String subject,
            String predicate,
            String object,
            Float confidence,
            UUID sourceId,
            String status,
            String createdBy,
            OffsetDateTime createdAt,
            OffsetDateTime validFrom,
            OffsetDateTime validUntil
    ) {
        dslContext.execute(
                """
                INSERT INTO facts (
                    id, parent_id, subject, predicate, object, confidence, source_id,
                    status, created_by, created_at, valid_from, valid_until
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?::timestamptz)
                """,
                id, parentId, subject, predicate, object, confidence, sourceId,
                status, createdBy, createdAt, validFrom, validUntil
        );
    }

    private void insertTunnel(
            UUID id,
            UUID fromDrawer,
            UUID toDrawer,
            String relation,
            String note,
            String status,
            String createdBy,
            OffsetDateTime createdAt,
            OffsetDateTime validFrom,
            OffsetDateTime validUntil
    ) {
        dslContext.execute(
                """
                INSERT INTO tunnels (
                    id, from_drawer, to_drawer, relation, note, status, created_by, created_at, valid_from, valid_until
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?::timestamptz)
                """,
                id, fromDrawer, toDrawer, relation, note, status, createdBy, createdAt, validFrom, validUntil
        );
    }

    private void insertReference(
            UUID id,
            String title,
            String url,
            String author,
            String refType,
            String status,
            String notes,
            String[] tags,
            Integer importance,
            OffsetDateTime createdAt
    ) {
        dslContext.execute(
                """
                INSERT INTO references_ (
                    id, title, url, author, ref_type, status, notes, tags, importance, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz)
                """,
                id, title, url, author, refType, status, notes, tags, importance, createdAt
        );
    }

    private void linkReference(
            UUID id,
            UUID drawerId,
            UUID referenceId,
            String relation,
            OffsetDateTime createdAt
    ) {
        dslContext.execute(
                """
                INSERT INTO drawer_references (
                    id, drawer_id, reference_id, relation, created_at
                ) VALUES (?, ?, ?, ?, ?::timestamptz)
                """,
                id, drawerId, referenceId, relation, createdAt
        );
    }

    private void insertAgent(
            String name,
            String focus,
            String schedule,
            OffsetDateTime createdAt
    ) {
        dslContext.execute(
                """
                INSERT INTO agents (
                    name, focus, schedule, created_at
                ) VALUES (?, ?, ?, ?::timestamptz)
                """,
                name, focus, schedule, createdAt
        );
    }

    private void insertDiaryEntry(
            UUID id,
            String agent,
            String entry,
            OffsetDateTime createdAt
    ) {
        dslContext.execute(
                """
                INSERT INTO agent_diary (
                    id, agent, entry, created_at
                ) VALUES (?, ?, ?, ?::timestamptz)
                """,
                id, agent, entry, createdAt
        );
    }

    private void insertBlueprint(
            UUID id,
            String wing,
            String title,
            String narrative,
            String[] hallOrder,
            UUID[] keyDrawers,
            String createdBy,
            OffsetDateTime createdAt,
            OffsetDateTime validFrom,
            OffsetDateTime validUntil
    ) {
        dslContext.execute(
                """
                INSERT INTO blueprints (
                    id, wing, title, narrative, hall_order, key_drawers, created_by, created_at, valid_from, valid_until
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?::timestamptz)
                """,
                id, wing, title, narrative, hallOrder, keyDrawers, createdBy, createdAt, validFrom, validUntil
        );
    }

    private void insertIdentity(
            String key,
            String content,
            Integer tokenCount,
            OffsetDateTime updatedAt
    ) {
        dslContext.execute(
                """
                INSERT INTO identity (
                    key, content, token_count, updated_at
                ) VALUES (?, ?, ?, ?::timestamptz)
                ON CONFLICT (key) DO UPDATE SET content = excluded.content, token_count = excluded.token_count, updated_at = excluded.updated_at
                """,
                key, content, tokenCount, updatedAt
        );
    }

    private JsonNode callToolContent(String toolName, Map<String, Object> arguments) throws Exception {
        MvcResult result = mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
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
        TokenService tokenService() {
            return new com.hivemem.auth.support.FixedTokenService(token -> switch (token) {
                case "good-token" -> Optional.of(new AuthPrincipal("token-1", AuthRole.WRITER));
                default -> Optional.empty();
            });
        }

        @Bean
        @org.springframework.context.annotation.Primary
        EmbeddingClient embeddingClient() {
            return new FixedEmbeddingClient();
        }
    }
}
