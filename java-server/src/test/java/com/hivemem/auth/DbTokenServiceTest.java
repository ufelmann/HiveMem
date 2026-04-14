package com.hivemem.auth;

import com.hivemem.embedding.EmbeddingClient;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class DbTokenServiceTest {

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
    @Qualifier("dbTokenService")
    private TokenService dbTokenService;

    @Autowired
    private DSLContext dslContext;

    @MockBean(name = "httpEmbeddingClient")
    private EmbeddingClient embeddingClient;

    @BeforeEach
    void resetDatabase() {
        dslContext.execute("TRUNCATE TABLE api_tokens");
    }

    @Test
    void validatesCommittedTokenFromDatabase() throws Exception {
        dslContext.execute("""
                INSERT INTO api_tokens (token_hash, name, role)
                VALUES (?, ?, ?)
                """, sha256("good-token"), "admin-user", "admin");

        var principal = dbTokenService.validateToken("good-token");

        assertThat(principal).isPresent();
        assertThat(principal.orElseThrow().name()).isEqualTo("admin-user");
        assertThat(principal.orElseThrow().role()).isEqualTo(AuthRole.ADMIN);
    }

    @Test
    void rejectsUnknownToken() {
        assertThat(dbTokenService.validateToken("missing-token")).isEmpty();
    }

    @Test
    void rejectsRevokedToken() throws Exception {
        insertToken(
                "revoked-user",
                "revoked-token",
                "admin",
                OffsetDateTime.now().plusHours(1),
                OffsetDateTime.now()
        );

        assertThat(dbTokenService.validateToken("revoked-token")).isEmpty();
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        insertToken(
                "expired-user",
                "expired-token",
                "admin",
                OffsetDateTime.now().minusHours(1),
                null
        );

        assertThat(dbTokenService.validateToken("expired-token")).isEmpty();
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
