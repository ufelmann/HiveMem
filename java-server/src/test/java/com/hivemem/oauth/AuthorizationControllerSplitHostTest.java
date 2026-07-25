package com.hivemem.oauth;

import com.hivemem.auth.AccessJwtResolverTestSupport;
import com.hivemem.auth.HumanPrincipalResolver;
import com.hivemem.auth.TokenService;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Split-host deployments serve the machine hostname (no Cloudflare Access, bearer tokens)
 * and the human hostname (Access-protected GUI) from one origin. The OAuth consent step is
 * the one human step in an otherwise machine-driven flow, and discovery advertises it on
 * the machine host — where Cloudflare injects no JWT. These tests pin that such a request
 * is redirected to the human host, and that the redirect can never loop.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import(AuthorizationControllerSplitHostTest.TestConfig.class)
@TestPropertySource(properties = {
        "hivemem.oauth.enabled=true",
        "hivemem.oauth.issuer=https://mem.example.com",
        "hivemem.oauth.authorize-redirect-base-url=https://gui.example.com",
        "hivemem.access.enabled=true",
        "hivemem.access.team-domain=" + AccessJwtTestFixtures.TEAM_DOMAIN,
        "hivemem.access.audience=" + AccessJwtTestFixtures.AUDIENCE
})
class AuthorizationControllerSplitHostTest {

    private static final String REDIRECT_URI = "https://claude.ai/callback";
    private static final String MACHINE_HOST = "mem.example.com";
    private static final String GUI_HOST = "gui.example.com";
    private static final String KNOWN_EMAIL = "mika@example.com";

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
    }

    @Autowired MockMvc mockMvc;
    @Autowired DSLContext dsl;

    @BeforeEach
    void seed() {
        dsl.execute("TRUNCATE TABLE oauth_tokens, oauth_authorization_codes, oauth_clients CASCADE");
        dsl.execute("DELETE FROM api_tokens WHERE name LIKE 'split-host-oauth-%'");
        dsl.execute("""
                INSERT INTO oauth_clients (client_id, client_name, redirect_uris)
                VALUES (?, ?, ARRAY[?]::TEXT[])
                ON CONFLICT (client_id) DO NOTHING
                """, AccessJwtTestFixtures.registeredClientId(), "Split Host Test Client", REDIRECT_URI);
        dsl.execute("""
                INSERT INTO api_tokens (token_hash, name, role, email)
                VALUES (?, ?, 'admin', ?)
                """, "test-hash-" + UUID.randomUUID(), "split-host-oauth-" + UUID.randomUUID(), KNOWN_EMAIL);
    }

    /** The full query string a real client sends, used verbatim in the assertions. */
    private static String query() {
        return "response_type=code"
                + "&client_id=" + AccessJwtTestFixtures.registeredClientId()
                + "&redirect_uri=https%3A%2F%2Fclaude.ai%2Fcallback"
                + "&scope=read%20write"
                + "&state=xy%20z%2F%2B1"
                + "&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
                + "&code_challenge_method=S256";
    }

    @Test
    void unauthenticatedRequestOnMachineHostRedirectsToGuiHostWithQueryVerbatim() throws Exception {
        mockMvc.perform(get(URI.create("https://" + MACHINE_HOST + "/oauth/authorize?" + query())))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "https://gui.example.com/oauth/authorize?" + query()));
    }

    @Test
    void unauthenticatedRequestOnGuiHostIsForbiddenNotRedirected() throws Exception {
        mockMvc.perform(get(URI.create("https://" + GUI_HOST + "/oauth/authorize?" + query())))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void hostComparisonIsCaseInsensitive() throws Exception {
        mockMvc.perform(get(URI.create("https://GUI.EXAMPLE.COM/oauth/authorize?" + query())))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownClientIsRejectedBeforeAnyRedirect() throws Exception {
        mockMvc.perform(get("/oauth/authorize")
                        .queryParam("response_type", "code")
                        .queryParam("client_id", "hm_does_not_exist")
                        .queryParam("redirect_uri", REDIRECT_URI)
                        .queryParam("code_challenge", "abc")
                        .queryParam("code_challenge_method", "S256")
                        .with(request -> { request.setServerName(MACHINE_HOST); return request; }))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void authenticatedRequestOnMachineHostRendersConsentWithoutRedirect() throws Exception {
        mockMvc.perform(get(URI.create("https://" + MACHINE_HOST + "/oauth/authorize?" + query()))
                        .header("Cf-Access-Jwt-Assertion", AccessJwtTestFixtures.signedFor(KNOWN_EMAIL)))
                .andExpect(status().isOk());
    }

    @Test
    void authenticatedRequestOnGuiHostRendersConsentPage() throws Exception {
        mockMvc.perform(get(URI.create("https://" + GUI_HOST + "/oauth/authorize?" + query()))
                        .header("Cf-Access-Jwt-Assertion", AccessJwtTestFixtures.signedFor(KNOWN_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("requests access to HiveMem")));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        @Primary
        HumanPrincipalResolver testAccessJwtResolver(TokenService tokenService) {
            return AccessJwtResolverTestSupport.forTesting(
                    AccessJwtTestFixtures.TEAM_DOMAIN, AccessJwtTestFixtures.AUDIENCE,
                    AccessJwtTestFixtures.rsaKey().toPublicJWK(), tokenService);
        }

        @Bean
        @Primary
        EmbeddingClient testEmbeddingClient() {
            return new FixedEmbeddingClient();
        }
    }
}
