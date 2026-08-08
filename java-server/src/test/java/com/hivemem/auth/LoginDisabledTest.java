package com.hivemem.auth;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The token-in-a-password-field page must be gone in Access mode — otherwise it is a back
 * door next to Cloudflare Access and the hardening is void. Every one of these assertions is
 * about the status code rather than the SPA shell: {@link com.hivemem.web.SpaController}'s
 * method-agnostic catch-all would otherwise swallow {@code /login} and {@code /logout} with a
 * silent 200.
 *
 * <p>The submitting path — any non-GET {@code /login} — and all of {@code /logout} answer 410.
 * The single exception is the bare {@code GET /login}, which redirects into the app: after a
 * successful Access challenge the edge returns the browser to the URL that triggered it, and
 * that URL is {@code /login} precisely because it is the one SPA entry point the service
 * worker does not answer from its precache. A 410 there stranded the user on a blank page.
 * See {@code documentation/auth.md}.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import(LoginDisabledTest.TestConfig.class)
@TestPropertySource(properties = {
        "hivemem.access.enabled=true",
        "hivemem.access.team-domain=https://example.cloudflareaccess.com",
        "hivemem.access.audience=test-aud"
})
class LoginDisabledTest {

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

    @Autowired MockMvc mockMvc;

    /** The submitting path is the back door, and it stays closed. */
    @Test
    void postLoginIsGoneInAccessMode() throws Exception {
        mockMvc.perform(post("/login")).andExpect(status().isGone());
    }

    /** Cloudflare's post-challenge return hop lands here and must be handed back to the app. */
    @Test
    void getLoginRedirectsIntoTheAppInAccessMode() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/"));
    }

    /**
     * A cached redirect would survive the session it was issued for and could bounce a later,
     * legitimately unauthenticated visit straight past the Access challenge.
     */
    @Test
    void getLoginRedirectIsNotCached() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void logoutIsGoneInAccessMode() throws Exception {
        mockMvc.perform(get("/logout")).andExpect(status().isGone());
        mockMvc.perform(post("/logout")).andExpect(status().isGone());
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
