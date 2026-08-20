package com.hivemem.web;

import com.hivemem.auth.AuthRole;
import com.hivemem.auth.LoginController;
import com.hivemem.auth.TokenService;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression coverage for static resources served with no {@code Cache-Control} header
 * at all, only {@code Last-Modified}. With no directive, browsers apply heuristic
 * freshness to the SPA shell, so an expired Cloudflare Access session is never
 * revalidated and the app dead-ends on its error screen.
 *
 * <p>The SPA shell (root, deep links, {@code /index.html} directly) and {@code /sw.js}
 * must be {@code no-cache} (stored, always revalidated — 304s still work). Content-hashed
 * build output under {@code /assets/} may be cached hard since the filename changes on
 * every content change.
 *
 * <p>Full Spring context (not a {@code @WebMvcTest} slice) because the fix lives in the
 * resource-handler wiring itself ({@link StaticResourceCacheConfig}), which a slice test
 * would either have to import by hand or risk silently not exercising.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import(StaticResourceCacheHeadersTest.TestConfig.class)
class StaticResourceCacheHeadersTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem")
            .withUsername("hivemem")
            .withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null
                            ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig())
                            .withSecurityOpts(List.of("apparmor=unconfined"))));

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    @Qualifier("dbTokenService")
    TokenService tokenService;

    @Autowired
    DSLContext dslContext;

    @BeforeEach
    void resetTokens() {
        dslContext.execute("TRUNCATE TABLE api_tokens CASCADE");
    }

    // Build a session the way LoginController does — mint a real token via the real
    // TokenService and store it under LoginController.SESSION_TOKEN_KEY. Static
    // resources still sit behind HumanAuthFilter's session gate in legacy mode
    // (except the exempt PWA assets), so root/deep-link requests need a real session
    // to reach the resource handler at all instead of being redirected to /login.
    private MockHttpSession loggedInSession() {
        String plaintext = tokenService.createToken(
                "cache-headers-test-admin", AuthRole.ADMIN, null, null, null);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(LoginController.SESSION_TOKEN_KEY, plaintext);
        return session;
    }

    @Test
    void indexHtmlServedDirectlyIsNoCache() throws Exception {
        mockMvc.perform(get("/index.html").session(loggedInSession()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"));
    }

    @Test
    void rootForwardsToIndexHtmlWithNoCache() throws Exception {
        mockMvc.perform(get("/").session(loggedInSession()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"));
    }

    @Test
    void spaDeepLinkForwardsToIndexHtmlWithNoCache() throws Exception {
        mockMvc.perform(get("/some/deep/route").session(loggedInSession()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"));
    }

    @Test
    void serviceWorkerIsNoCache() throws Exception {
        mockMvc.perform(get("/sw.js"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"));
    }

    @Test
    void hashedAssetIsCachedImmutableForOneYear() throws Exception {
        mockMvc.perform(get("/assets/app-test123.js").session(loggedInSession()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        @Primary
        EmbeddingClient embeddingClient() {
            return new FixedEmbeddingClient();
        }
    }
}
