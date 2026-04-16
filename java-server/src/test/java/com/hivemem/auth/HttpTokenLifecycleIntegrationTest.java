package com.hivemem.auth;

import com.hivemem.embedding.EmbeddingClient;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class HttpTokenLifecycleIntegrationTest {

    private static final String TOOLS_LIST_REQUEST = """
            {"jsonrpc":"2.0","id":1,"method":"tools/list"}
            """;

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

    @MockBean(name = "httpEmbeddingClient")
    private EmbeddingClient embeddingClient;

    @BeforeEach
    void resetTokens() {
        rateLimiter.clearAll();
        dslContext.execute("TRUNCATE TABLE api_tokens");
    }

    @Test
    void revokedTokenReturnsUnauthorizedOverMcp() throws Exception {
        insertToken(
                "revoked-admin",
                "revoked-token",
                "admin",
                OffsetDateTime.now().plusHours(1),
                OffsetDateTime.now()
        );

        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer revoked-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TOOLS_LIST_REQUEST))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenReturnsUnauthorizedOverMcp() throws Exception {
        insertToken(
                "expired-admin",
                "expired-token",
                "admin",
                OffsetDateTime.now().minusHours(1),
                null
        );

        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer expired-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TOOLS_LIST_REQUEST))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validAdminTokenRoundTripsOverMcp() throws Exception {
        insertToken(
                "admin-user",
                "admin-token",
                "admin",
                OffsetDateTime.now().plusHours(1),
                null
        );

        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TOOLS_LIST_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools[*].name", hasItem("hivemem_health")))
                .andExpect(jsonPath("$.result.tools[*].name", hasItem("hivemem_add_drawer")));
    }

    private void insertToken(
            String name,
            String plaintext,
            String role,
            OffsetDateTime expiresAt,
            OffsetDateTime revokedAt
    ) throws Exception {
        dslContext.execute("""
                INSERT INTO api_tokens (token_hash, name, role, expires_at, revoked_at)
                VALUES (?, ?, ?, ?::timestamptz, ?::timestamptz)
                """, sha256(plaintext), name, role, expiresAt, revokedAt);
    }

    private static String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
