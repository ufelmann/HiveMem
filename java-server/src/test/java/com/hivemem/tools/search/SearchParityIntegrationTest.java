package com.hivemem.tools.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.auth.RateLimiter;
import com.hivemem.auth.TokenService;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SearchParityIntegrationTest.TestConfig.class)
@Testcontainers
class SearchParityIntegrationTest {

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

    @MockBean(name = "httpEmbeddingClient")
    private EmbeddingClient httpEmbeddingClient;

    @BeforeEach
    void resetDatabase() {
        rateLimiter.clearAll();
        dslContext.execute("TRUNCATE TABLE access_log, agent_diary, drawer_references, references_, blueprints, identity, agents, facts, tunnels, drawers CASCADE");
        dslContext.execute("REFRESH MATERIALIZED VIEW drawer_popularity");
        FixedEmbeddingClient fixedEmbeddingClient = new FixedEmbeddingClient();
        when(httpEmbeddingClient.encodeQuery(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> fixedEmbeddingClient.encodeQuery(invocation.getArgument(0, String.class)));
        when(httpEmbeddingClient.encodeDocument(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> fixedEmbeddingClient.encodeDocument(invocation.getArgument(0, String.class)));
    }

    @Test
    void rankedSearchReturnsAllScoreComponents() throws Exception {
        insertDrawer(
                UUID.fromString("00000000-0000-0000-0000-000000000801"),
                "PostgreSQL vector search with pgvector",
                "eng",
                "db",
                "facts",
                2,
                "pgvector search",
                "committed",
                OffsetDateTime.parse("2026-04-03T10:00:00Z")
        );

        JsonNode results = callTool("writer-token", "hivemem_search", Map.of(
                "query", "vector search",
                "limit", 10
        ));

        JsonNode first = results.get(0);
        assertThat(first.path("score_semantic").isNumber()).isTrue();
        assertThat(first.path("score_keyword").isNumber()).isTrue();
        assertThat(first.path("score_recency").isNumber()).isTrue();
        assertThat(first.path("score_importance").isNumber()).isTrue();
        assertThat(first.path("score_popularity").isNumber()).isTrue();
        assertThat(first.path("score_total").isNumber()).isTrue();
        assertThat(first.path("score_total").asDouble()).isGreaterThan(0.0d);
    }

    @Test
    void rankedSearchHonorsWingFilter() throws Exception {
        insertDrawer(
                UUID.fromString("00000000-0000-0000-0000-000000000811"),
                "Engineering topic",
                "eng",
                "planning",
                "facts",
                3,
                "Engineering topic",
                "committed",
                OffsetDateTime.parse("2026-04-03T11:00:00Z")
        );
        insertDrawer(
                UUID.fromString("00000000-0000-0000-0000-000000000812"),
                "Personal topic",
                "personal",
                "planning",
                "facts",
                3,
                "Personal topic",
                "committed",
                OffsetDateTime.parse("2026-04-03T11:00:00Z")
        );

        JsonNode results = callTool("writer-token", "hivemem_search", Map.of(
                "query", "topic",
                "wing", "eng"
        ));

        assertThat(results).hasSize(1);
        assertThat(textValues(results, "wing")).containsExactly("eng");
    }

    @Test
    void rankedSearchHonorsHallFilter() throws Exception {
        insertDrawer(
                UUID.fromString("00000000-0000-0000-0000-000000000821"),
                "Search discovery note",
                "eng",
                "discoveries",
                "facts",
                2,
                "Search discovery",
                "committed",
                OffsetDateTime.parse("2026-04-03T12:00:00Z")
        );
        insertDrawer(
                UUID.fromString("00000000-0000-0000-0000-000000000822"),
                "Search fact note",
                "eng",
                "facts",
                "facts",
                2,
                "Search fact",
                "committed",
                OffsetDateTime.parse("2026-04-03T12:00:00Z")
        );

        JsonNode results = callTool("writer-token", "hivemem_search", Map.of(
                "query", "search",
                "hall", "discoveries"
        ));

        assertThat(results).hasSize(1);
        assertThat(textValues(results, "hall")).containsExactly("discoveries");
    }

    @Test
    void popularityAffectsRankingDeterministically() throws Exception {
        UUID popularDrawerId = UUID.fromString("00000000-0000-0000-0000-000000000831");
        UUID regularDrawerId = UUID.fromString("00000000-0000-0000-0000-000000000832");

        insertDrawer(
                popularDrawerId,
                "Docker knowledge alpha",
                "eng",
                "infra",
                "facts",
                2,
                "Docker knowledge alpha",
                "committed",
                OffsetDateTime.parse("2026-04-03T13:00:00Z")
        );
        insertDrawer(
                regularDrawerId,
                "Docker knowledge beta",
                "eng",
                "infra",
                "facts",
                2,
                "Docker knowledge beta",
                "committed",
                OffsetDateTime.parse("2026-04-03T13:00:00Z")
        );

        for (int i = 0; i < 5; i++) {
            callTool("admin-token", "hivemem_log_access", Map.of(
                    "drawer_id", popularDrawerId.toString()
            ));
        }
        callTool("admin-token", "hivemem_refresh_popularity", Map.of());

        JsonNode results = callTool("writer-token", "hivemem_search", Map.of(
                "query", "docker knowledge",
                "weight_semantic", 0.0d,
                "weight_keyword", 0.0d,
                "weight_recency", 0.0d,
                "weight_importance", 0.0d,
                "weight_popularity", 1.0d
        ));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).path("id").asText()).isEqualTo(popularDrawerId.toString());
        assertThat(results.get(0).path("score_popularity").asDouble())
                .isGreaterThan(results.get(1).path("score_popularity").asDouble());
        assertThat(results.get(0).path("score_total").asDouble())
                .isEqualTo(results.get(0).path("score_popularity").asDouble());
    }

    @Test
    void pendingDrawersAreExcludedFromRankedSearch() throws Exception {
        UUID committedDrawerId = UUID.fromString("00000000-0000-0000-0000-000000000841");
        UUID pendingDrawerId = UUID.fromString("00000000-0000-0000-0000-000000000842");

        insertDrawer(
                committedDrawerId,
                "Topic drawer committed",
                "eng",
                "planning",
                "facts",
                2,
                "Committed topic",
                "committed",
                OffsetDateTime.parse("2026-04-03T14:00:00Z")
        );
        insertDrawer(
                pendingDrawerId,
                "Topic drawer pending",
                "eng",
                "planning",
                "facts",
                2,
                "Pending topic",
                "pending",
                OffsetDateTime.parse("2026-04-03T14:00:00Z")
        );

        JsonNode results = callTool("writer-token", "hivemem_search", Map.of(
                "query", "topic drawer"
        ));

        assertThat(textValues(results, "id")).contains(committedDrawerId.toString());
        assertThat(textValues(results, "id")).doesNotContain(pendingDrawerId.toString());
    }

    private JsonNode callTool(String token, String toolName, Map<String, Object> arguments) throws Exception {
        MvcResult result = mockMvc.perform(post("/mcp")
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
        return body.path("result").path("content").get(0);
    }

    private List<String> textValues(JsonNode results, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode row : results) {
            values.add(row.path(field).asText());
        }
        return values;
    }

    private void insertDrawer(
            UUID id,
            String content,
            String wing,
            String hall,
            String room,
            Integer importance,
            String summary,
            String status,
            OffsetDateTime createdAt
    ) {
        dslContext.execute(
                """
                INSERT INTO drawers (
                    id, content, wing, hall, room, importance, summary, status, created_by, created_at, valid_from, valid_until
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?::timestamptz)
                """,
                id, content, wing, hall, room, importance, summary, status, "writer-1", createdAt, createdAt, null
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        @org.springframework.context.annotation.Primary
        TokenService tokenService() {
            return new com.hivemem.auth.support.FixedTokenService(token -> switch (token) {
                case "writer-token" -> Optional.of(new AuthPrincipal("writer-1", AuthRole.WRITER));
                case "admin-token" -> Optional.of(new AuthPrincipal("admin-1", AuthRole.ADMIN));
                default -> Optional.empty();
            });
        }

    }
}
