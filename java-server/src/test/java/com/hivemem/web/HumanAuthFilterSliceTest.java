package com.hivemem.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hivemem.auth.AccessJwtResolver;
import com.hivemem.auth.AccessProperties;
import com.hivemem.auth.AuthFilter;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.auth.HumanPrincipalResolver;
import com.hivemem.auth.LoginController;
import com.hivemem.auth.RateLimiter;
import com.hivemem.auth.SessionResolver;
import com.hivemem.auth.TokenService;
import com.hivemem.auth.support.FixedTokenService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = HumanAuthFilterSliceTest.TestController.class)
@Import({HumanAuthFilter.class, AuthFilter.class, RateLimiter.class, com.hivemem.auth.SecurityProperties.class,
        HumanAuthFilterSliceTest.TestConfig.class})
class HumanAuthFilterSliceTest {

    @Autowired
    MockMvc mockMvc;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        @Primary
        TokenService tokenService() {
            return new FixedTokenService(token ->
                    "valid-token".equals(token)
                            ? Optional.of(new AuthPrincipal("alice", AuthRole.READER))
                            : Optional.empty());
        }

        // Legacy mode (the only mode this WebMvcTest exercises): SessionResolver is the
        // active HumanPrincipalResolver, backed by the fixed tokenService() above.
        @Bean
        HumanPrincipalResolver humanPrincipalResolver(TokenService tokenService) {
            return new SessionResolver(tokenService);
        }

        @Bean
        AccessProperties accessProperties() {
            return new AccessProperties();
        }

        @Bean
        TestController testController() {
            return new TestController();
        }
    }

    @Test
    void requestWithoutSessionRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/some-page"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void requestWithValidSessionPasses() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(LoginController.SESSION_TOKEN_KEY, "valid-token");

        mockMvc.perform(get("/some-page").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @Test
    void requestWithInvalidTokenInSessionRedirectsToLogin() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(LoginController.SESSION_TOKEN_KEY, "bad-token");

        mockMvc.perform(get("/some-page").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void loginPathIsNotFiltered() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk());
    }

    @Test
    void mcpPathWithoutSessionContinuesChain() throws Exception {
        // Without session, /mcp passes through to AuthFilter which returns 401 (no Bearer token)
        mockMvc.perform(get("/mcp"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mcpPathWithInvalidSessionContinuesChain() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(LoginController.SESSION_TOKEN_KEY, "bad-token");

        // Invalid session token on /mcp: HumanAuthFilter invalidates session and passes to AuthFilter,
        // which returns 401 (no Bearer token present).
        mockMvc.perform(get("/mcp").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void vistieriePathWithoutSessionContinuesChain() throws Exception {
        // Unauthenticated /vistierie requests must pass through HumanAuthFilter
        // (mirrors /hooks behaviour) — NOT redirected to /login.
        mockMvc.perform(get("/vistierie/tools/find_isolated_cells"))
                .andExpect(status().isOk());
    }

    @Test
    void syncPathWithoutSessionContinuesChain() throws Exception {
        // Peer sync is bearer-authed: without a session, /sync must pass through to
        // AuthFilter (which 401s absent a Bearer token) — NOT redirect to /login.
        // A redirect would feed the peer's RestClient the login page and silently
        // no-op replication.
        mockMvc.perform(get("/sync/ops"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void syncPathWithBearerTokenReachesController() throws Exception {
        mockMvc.perform(get("/sync/ops").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @Test
    void adminPathWithoutSessionOrBearerRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/admin/peers"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void adminPathWithBearerTokenContinuesToAuthFilter() throws Exception {
        // Bearer-authed /admin (CLI/scripts) must defer to AuthFilter, which validates
        // the token — valid tokens reach the controller, invalid ones get 401.
        mockMvc.perform(get("/admin/peers").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
        mockMvc.perform(get("/admin/peers").header("Authorization", "Bearer bad-token"))
                .andExpect(status().isUnauthorized());
    }

    // Unit tests for PWA asset exemption (new tests covering Task 1)
    private static class UnitTestHelper {
        private final HumanAuthFilter filter =
                new HumanAuthFilter(mock(HumanPrincipalResolver.class), new AccessProperties());

        private boolean skip(String uri) {
            return filter.shouldNotFilter(new MockHttpServletRequest("GET", uri));
        }
    }

    @Test
    void pwaShellAssetsBypassAuthWithoutSession() {
        UnitTestHelper helper = new UnitTestHelper();
        for (String p : List.of(
                "/manifest.webmanifest", "/sw.js",
                "/pwa-64x64.png", "/pwa-192x192.png", "/pwa-512x512.png",
                "/maskable-icon-512x512.png", "/apple-touch-icon-180x180.png")) {
            assertThat(helper.skip(p)).as("PWA asset %s must skip the session filter", p).isTrue();
        }
    }

    @Test
    void workboxChunkBypassesAuthWithoutSession() {
        UnitTestHelper helper = new UnitTestHelper();
        // Real filename observed in dist/sw.js.
        assertThat(helper.skip("/workbox-d73a1edf.js")).isTrue();
        // A different build produces a different content hash — still matches the pattern.
        assertThat(helper.skip("/workbox-0123abcd.js")).isTrue();
    }

    @Test
    void workboxLookalikesStayFiltered() {
        UnitTestHelper helper = new UnitTestHelper();
        assertThat(helper.skip("/workbox-.js")).isFalse();
        assertThat(helper.skip("/workbox-xyz.js")).isFalse();
        assertThat(helper.skip("/workbox-abc123.js.map")).isFalse();
        assertThat(helper.skip("/sub/workbox-abc123.js")).isFalse();
        assertThat(helper.skip("/workbox-abc123.js/../secret")).isFalse();
        assertThat(helper.skip("/workbox-abc123.js/..%2f..%2fsecret")).isFalse();
        assertThat(helper.skip("/../workbox-abc123.js")).isFalse();
    }

    @Test
    void apiStillFilteredWithoutSession() {
        UnitTestHelper helper = new UnitTestHelper();
        assertThat(helper.skip("/api/attachments")).isFalse();
        assertThat(helper.skip("/api/status")).isFalse();
    }

    @Test
    void spaRootStillFiltered() {
        UnitTestHelper helper = new UnitTestHelper();
        assertThat(helper.skip("/")).isFalse();
        assertThat(helper.skip("/hive")).isFalse();
    }

    // Unit tests for the denial-logging change: assert on the captured log output
    // (never stdout), and that every status/redirect stays exactly as before.
    private ListAppender<ILoggingEvent> captureLog() {
        ch.qos.logback.classic.Logger filterLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(HumanAuthFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        filterLogger.addAppender(appender);
        return appender;
    }

    private void doFilter(HumanAuthFilter filter, MockHttpServletRequest request,
                          MockHttpServletResponse response) throws Exception {
        // Same package as HumanAuthFilter: its protected doFilterInternal is directly callable.
        filter.doFilterInternal(request, response, mock(FilterChain.class));
    }

    @Test
    void deniedApiRequestWithoutHeaderLogs401AndHeaderAbsent() throws Exception {
        HumanAuthFilter filter = new HumanAuthFilter(
                request -> Optional.empty(), new AccessProperties());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/tools/call");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ListAppender<ILoggingEvent> appender = captureLog();

        try {
            doFilter(filter, request, response);
        } finally {
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(HumanAuthFilter.class))
                    .detachAppender(appender);
        }

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(appender.list.stream()
                        .filter(e -> e.getLevel() == Level.WARN)
                        .map(ILoggingEvent::getFormattedMessage))
                .as("got: " + appender.list)
                .anyMatch(m -> m.contains("401") && m.contains("/api/tools/call")
                        && m.contains("absent"));
    }

    @Test
    void deniedSpaRouteInAccessModeLogs403() throws Exception {
        AccessProperties accessProperties = new AccessProperties();
        accessProperties.setEnabled(true);
        HumanAuthFilter filter = new HumanAuthFilter(request -> Optional.empty(), accessProperties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/graph");
        request.addHeader(AccessJwtResolver.HEADER, "some-jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ListAppender<ILoggingEvent> appender = captureLog();

        try {
            doFilter(filter, request, response);
        } finally {
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(HumanAuthFilter.class))
                    .detachAppender(appender);
        }

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(appender.list.stream()
                        .filter(e -> e.getLevel() == Level.WARN)
                        .map(ILoggingEvent::getFormattedMessage))
                .as("got: " + appender.list)
                .anyMatch(m -> m.contains("403") && m.contains("/graph") && m.contains("present"));
    }

    @Test
    void deniedSpaRouteInLegacyModeLogsRedirect() throws Exception {
        HumanAuthFilter filter = new HumanAuthFilter(
                request -> Optional.empty(), new AccessProperties());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/graph");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ListAppender<ILoggingEvent> appender = captureLog();

        try {
            doFilter(filter, request, response);
        } finally {
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(HumanAuthFilter.class))
                    .detachAppender(appender);
        }

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/login");
        assertThat(appender.list.stream()
                        .filter(e -> e.getLevel() == Level.WARN)
                        .map(ILoggingEvent::getFormattedMessage))
                .as("got: " + appender.list)
                .anyMatch(m -> m.contains("302") && m.contains("/graph") && m.contains("absent"));
    }

    @Test
    void deniedApiRequestWithBlankHeaderLogsHeaderAbsent() throws Exception {
        // A blank header must be treated the same as no header at all: AccessJwtResolver
        // already does (AccessJwtResolver.java:96), and reporting it as "present" here would
        // produce a signature the operator-facing table has no row for — a proxy forwarding
        // an empty `Cf-Access-Jwt-Assertion:` header must not look like a rejected JWT.
        HumanAuthFilter filter = new HumanAuthFilter(
                request -> Optional.empty(), new AccessProperties());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/tools/call");
        request.addHeader(AccessJwtResolver.HEADER, "");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ListAppender<ILoggingEvent> appender = captureLog();

        try {
            doFilter(filter, request, response);
        } finally {
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(HumanAuthFilter.class))
                    .detachAppender(appender);
        }

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(appender.list.stream()
                        .filter(e -> e.getLevel() == Level.WARN)
                        .map(ILoggingEvent::getFormattedMessage))
                .as("got: " + appender.list)
                .anyMatch(m -> m.contains("401") && m.contains("absent"));
    }

    @Test
    void realmScopedForbiddenLogsItsOwnMessageWithNoHeaderDiscriminator() throws Exception {
        // The principal here IS authenticated — this is an authorization denial, not any of
        // the three authentication-failure causes logDenial covers. Reusing that message and
        // its header discriminator would fabricate a fourth row that documentation/auth.md's
        // three-signature table doesn't have.
        AuthPrincipal realmScoped = new AuthPrincipal(
                "alice", AuthRole.READER, null, List.of("some-realm"), List.of("some-realm"));
        HumanAuthFilter filter = new HumanAuthFilter(
                request -> Optional.of(realmScoped), new AccessProperties());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/gui/stream");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ListAppender<ILoggingEvent> appender = captureLog();

        try {
            doFilter(filter, request, response);
        } finally {
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(HumanAuthFilter.class))
                    .detachAppender(appender);
        }

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(appender.list.stream()
                        .filter(e -> e.getLevel() == Level.WARN)
                        .map(ILoggingEvent::getFormattedMessage))
                .as("got: " + appender.list)
                .anyMatch(m -> m.contains("403") && m.contains("/api/gui/stream")
                        && m.contains("realm-scoped") && !m.contains("access-jwt header"));
    }

    @Test
    void authenticatedRequestLogsNothing() throws Exception {
        AuthPrincipal principal = new AuthPrincipal("alice", AuthRole.READER);
        HumanAuthFilter filter = new HumanAuthFilter(
                request -> Optional.of(principal), new AccessProperties());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/graph");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ListAppender<ILoggingEvent> appender = captureLog();

        try {
            doFilter(filter, request, response);
        } finally {
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(HumanAuthFilter.class))
                    .detachAppender(appender);
        }

        assertThat(appender.list).as("got: " + appender.list).isEmpty();
    }

    @Test
    void machinePassthroughLogsNothing() throws Exception {
        // Spec item 6: none of the five machine passthrough paths is a denial, so none may log.
        HumanAuthFilter filter = new HumanAuthFilter(
                request -> Optional.empty(), new AccessProperties());

        assertNoDenialLogged(filter, new MockHttpServletRequest("POST", "/mcp"));
        assertNoDenialLogged(filter, new MockHttpServletRequest("POST", "/hooks/some-hook"));
        assertNoDenialLogged(filter, new MockHttpServletRequest("GET", "/sync/ops"));
        assertNoDenialLogged(filter, new MockHttpServletRequest("GET", "/vistierie/tools/find_isolated_cells"));

        MockHttpServletRequest adminBearerRequest = new MockHttpServletRequest("GET", "/admin/peers");
        adminBearerRequest.addHeader("Authorization", "Bearer some-token");
        assertNoDenialLogged(filter, adminBearerRequest);
    }

    private void assertNoDenialLogged(HumanAuthFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ListAppender<ILoggingEvent> appender = captureLog();

        try {
            doFilter(filter, request, response);
        } finally {
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(HumanAuthFilter.class))
                    .detachAppender(appender);
        }

        assertThat(appender.list)
                .as("machine passthrough on %s is not a denial, got: %s", request.getRequestURI(), appender.list)
                .isEmpty();
    }

    @RestController
    static class TestController {
        @GetMapping({"/some-page", "/login", "/mcp", "/vistierie/tools/find_isolated_cells",
                "/sync/ops", "/admin/peers"})
        String index() { return "ok"; }
    }
}
