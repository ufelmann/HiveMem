package com.hivemem.auth;

import com.hivemem.embedding.EmbeddingClient;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Performance tests for HiveMem token management (Java / Caffeine / Spring Boot).
 *
 * <p>Measures actual timings and asserts performance bounds for:
 * <ul>
 *   <li>Caffeine cache hit latency</li>
 *   <li>Cache hit rate</li>
 *   <li>Cold (DB) token validation</li>
 *   <li>Bulk token creation via SQL</li>
 *   <li>Tool filtering performance</li>
 *   <li>HTTP request throughput over MockMvc</li>
 *   <li>Indexed lookup with many active tokens</li>
 * </ul>
 *
 * <p>Run with: {@code mvn test -Dgroups=performance -pl java-server -Dtest=TokenPerformanceTest}
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "hivemem.token-cache.enabled=true"
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class TokenPerformanceTest {

    private static final String TOOLS_LIST_REQUEST = """
            {"jsonrpc":"2.0","id":1,"method":"tools/list"}
            """;

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
    @Qualifier("cachedTokenService")
    private TokenService cachedTokenService;

    @Autowired
    private ToolPermissionService toolPermissionService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DSLContext dslContext;

    @Autowired
    private RateLimiter rateLimiter;

    @MockBean(name = "httpEmbeddingClient")
    private EmbeddingClient embeddingClient;

    @BeforeEach
    void resetDatabase() {
        rateLimiter.clearAll();
        dslContext.execute("TRUNCATE TABLE api_tokens");
    }

    // ── 1. Cached Validation Performance ─────────────────────────────────────

    @Test
    @Tag("performance")
    void cachedValidationIsFast() throws Exception {
        String plaintext = insertTokenReturningPlaintext("perf-cache", "admin");

        // Warmup: first call populates Caffeine cache
        assertThat(cachedTokenService.validateToken(plaintext)).isPresent();

        // Measure cached lookups
        int iterations = 1000;
        long startNs = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            var result = cachedTokenService.validateToken(plaintext);
            assertThat(result).isPresent();
        }
        long elapsedNs = System.nanoTime() - startNs;

        double avgMs = (elapsedNs / (double) iterations) / 1_000_000.0;
        System.out.println("\nCached validation: " + String.format("%.4f", avgMs)
                + "ms avg (" + iterations + " iterations)");
        assertThat(avgMs)
                .as("Cached validation too slow: %.4fms", avgMs)
                .isLessThan(0.1);
    }

    // ── 2. Cache Hit Rate ────────────────────────────────────────────────────

    @Test
    @Tag("performance")
    void cacheHitRateStaysAboveNinetyNinePercent() throws Exception {
        String plaintext = insertTokenReturningPlaintext("perf-hitrate", "admin");

        // Warmup: populate cache
        cachedTokenService.validateToken(plaintext);

        // CachedTokenService does not expose Caffeine stats, so we measure
        // indirectly: every call that completes in under 0.5ms is a cache hit.
        int iterations = 1000;
        int hits = 0;
        for (int i = 0; i < iterations; i++) {
            long callStart = System.nanoTime();
            cachedTokenService.validateToken(plaintext);
            long callNs = System.nanoTime() - callStart;
            double callMs = callNs / 1_000_000.0;
            if (callMs < 0.5) {
                hits++;
            }
        }

        double hitRate = hits / (double) iterations * 100;
        System.out.println("\nCache hit rate: " + String.format("%.1f", hitRate)
                + "% (" + hits + "/" + iterations + ")");
        assertThat(hitRate)
                .as("Cache hit rate too low: %.1f%%", hitRate)
                .isGreaterThan(99.0);
    }

    // ── 3. Cold DB Validation ────────────────────────────────────────────────

    @Test
    @Tag("performance")
    void coldDbValidationUnderFiveMs() throws Exception {
        // Create 100 unique tokens
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tokens.add(insertTokenReturningPlaintext("perf-cold-" + i, "reader"));
        }

        // Validate each once through dbTokenService (no cache)
        long startNs = System.nanoTime();
        for (String token : tokens) {
            var result = dbTokenService.validateToken(token);
            assertThat(result).isPresent();
        }
        long elapsedNs = System.nanoTime() - startNs;

        double avgMs = (elapsedNs / (double) tokens.size()) / 1_000_000.0;
        System.out.println("\nCold DB validation: " + String.format("%.4f", avgMs)
                + "ms avg (" + tokens.size() + " tokens)");
        assertThat(avgMs)
                .as("Cold validation too slow: %.4fms", avgMs)
                .isLessThan(5.0);
    }

    // ── 4. Bulk Token Creation ───────────────────────────────────────────────

    @Test
    @Tag("performance")
    void bulkCreationUnderTwoSeconds() throws Exception {
        long startNs = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            insertTokenReturningPlaintext("perf-bulk-" + i, "writer");
        }
        long elapsedNs = System.nanoTime() - startNs;

        double elapsedSec = elapsedNs / 1_000_000_000.0;
        double perTokenMs = (elapsedNs / 100.0) / 1_000_000.0;
        System.out.println("\nBulk creation: " + String.format("%.3f", elapsedSec)
                + "s for 100 tokens (" + String.format("%.2f", perTokenMs) + "ms each)");
        assertThat(elapsedSec)
                .as("Bulk creation too slow: %.3fs", elapsedSec)
                .isLessThan(2.0);
    }

    // ── 5. Tool Filtering Performance ────────────────────────────────────────

    @Test
    @Tag("performance")
    void toolFilteringPerformance() {
        // Build a list of 100+ tool names (some real, some fake)
        List<String> mockTools = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            mockTools.add("hivemem_tool_" + i);
        }
        // Add all real tool names from the admin set (superset of all roles)
        Set<String> allReal = toolPermissionService.allowedTools(AuthRole.ADMIN);
        mockTools.addAll(allReal);

        AuthRole[] roles = AuthRole.values();
        int iterations = 10_000;

        long startNs = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            for (AuthRole role : roles) {
                // Filter: retain only tools allowed for this role
                Set<String> allowed = toolPermissionService.allowedTools(role);
                mockTools.stream().filter(allowed::contains).toList();
            }
        }
        long elapsedNs = System.nanoTime() - startNs;

        long totalCalls = (long) iterations * roles.length;
        double avgMs = (elapsedNs / (double) totalCalls) / 1_000_000.0;
        System.out.println("\nTool filtering: " + String.format("%.6f", avgMs)
                + "ms avg (" + totalCalls + " calls)");
        assertThat(avgMs)
                .as("Tool filtering too slow: %.6fms", avgMs)
                .isLessThan(0.05);
    }

    // ── 6. HTTP Throughput ───────────────────────────────────────────────────

    @Test
    @Tag("performance")
    void httpThroughputAboveTwentyRps() throws Exception {
        String plaintext = insertTokenReturningPlaintext("perf-http", "admin");

        int iterations = 100;
        long startNs = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            mockMvc.perform(post("/mcp")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + plaintext)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(TOOLS_LIST_REQUEST))
                    .andExpect(status().isOk());
        }
        long elapsedNs = System.nanoTime() - startNs;

        double elapsedSec = elapsedNs / 1_000_000_000.0;
        double rps = iterations / elapsedSec;
        System.out.println("\nHTTP throughput: " + String.format("%.1f", rps)
                + " req/s (" + iterations + " requests in " + String.format("%.2f", elapsedSec) + "s)");
        assertThat(rps)
                .as("Throughput too low: %.1f req/s", rps)
                .isGreaterThan(20.0);
    }

    // ── 7. Indexed Lookup with Many Tokens ───────────────────────────────────

    @Test
    @Tag("performance")
    void indexedLookupUnderFiveMs() throws Exception {
        // Insert 500 reader tokens
        for (int i = 0; i < 500; i++) {
            insertTokenReturningPlaintext("perf-many-" + i, "reader");
        }

        // Create the target admin token
        String target = insertTokenReturningPlaintext("perf-many-target", "admin");

        // Warmup (prime the connection)
        dbTokenService.validateToken(target);

        // Measure repeated cold lookups of one token among 501
        int iterations = 50;
        long startNs = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            var result = dbTokenService.validateToken(target);
            assertThat(result).isPresent();
            assertThat(result.orElseThrow().role()).isEqualTo(AuthRole.ADMIN);
        }
        long elapsedNs = System.nanoTime() - startNs;

        double avgMs = (elapsedNs / (double) iterations) / 1_000_000.0;
        System.out.println("\nLookup with 501 tokens: " + String.format("%.4f", avgMs)
                + "ms avg (" + iterations + " iterations)");
        assertThat(avgMs)
                .as("Indexed lookup too slow: %.4fms", avgMs)
                .isLessThan(5.0);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Inserts a token directly into the database and returns the plaintext.
     * Mirrors the Python {@code create_token(pool, name, role)} helper.
     */
    private String insertTokenReturningPlaintext(String name, String role) throws Exception {
        String plaintext = "tok-" + UUID.randomUUID();
        dslContext.execute("""
                INSERT INTO api_tokens (token_hash, name, role)
                VALUES (?, ?, ?)
                """, sha256(plaintext), name, role);
        return plaintext;
    }

    private static String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
