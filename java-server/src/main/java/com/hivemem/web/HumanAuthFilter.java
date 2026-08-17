package com.hivemem.web;

import com.hivemem.auth.AccessJwtResolver;
import com.hivemem.auth.AccessProperties;
import com.hivemem.auth.AuthFilter;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.HumanPrincipalResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * Authenticates humans — browsers presenting either a Cloudflare Access JWT or a
 * legacy session cookie, depending on deployment mode (see {@link HumanPrincipalResolver}
 * / {@link AccessProperties}). Machine callers ({@code /mcp}, {@code /hooks}, {@code
 * /sync}, {@code /vistierie}, bearer-authed {@code /admin}) are explicitly none of this
 * filter's business: it passes them straight through to {@link AuthFilter} without
 * resolving a human principal at all. That early passthrough is what makes a browser
 * session unable to authenticate {@code /mcp} — the entire point of the human/machine
 * auth split.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HumanAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(HumanAuthFilter.class);

    private final HumanPrincipalResolver humanPrincipalResolver;
    private final AccessProperties accessProperties;

    /**
     * Public PWA shell assets. The browser fetches the manifest (via
     * {@code <link rel="manifest">}) and the periodic service-worker script update
     * without credentials, so these must bypass the session gate — otherwise the
     * sessionless request is 302'd to /login and the install/SW breaks. Exact match
     * only (getRequestURI() is unnormalized; never prefix-match a filename list).
     * These are non-sensitive static bytes (the app shell is public OSS anyway);
     * user data stays behind /api and /mcp. Names mirror the @vite-pwa/assets-generator
     * "minimal-2023" preset output — reconcile with the real dist/ output in Task 2.
     */
    private static final Set<String> PWA_PUBLIC_ASSETS = Set.of(
            "/manifest.webmanifest",
            "/sw.js",
            "/pwa-64x64.png",
            "/pwa-192x192.png",
            "/pwa-512x512.png",
            "/maskable-icon-512x512.png",
            "/apple-touch-icon-180x180.png");

    public HumanAuthFilter(HumanPrincipalResolver humanPrincipalResolver, AccessProperties accessProperties) {
        this.humanPrincipalResolver = humanPrincipalResolver;
        this.accessProperties = accessProperties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        // Legacy mode: LoginController serves /login (and /logout invalidates a session
        // that may not exist yet) without requiring a principal first — that's the whole
        // point of a login page. Access mode: LoginController is disabled and GoneController
        // takes over the same paths — 410 for /logout and for a POSTed login (a stale bookmark
        // stays diagnosable instead of silently 403'ing before it reaches a controller), and a
        // redirect to / for GET /login, which is where Access returns the browser after a
        // successful challenge.
        if (path.equals("/login") || path.equals("/logout")) return true;
        // The SPA must learn its auth mode before it can authenticate at all.
        if (path.equals("/api/config")) return true;
        // OAuth discovery + registration + token are public; the /oauth/authorize
        // endpoint handles its own login redirect when the user is unauthenticated.
        if (path.startsWith("/.well-known/oauth-")) return true;
        if (path.startsWith("/oauth/")) return true;
        if (PWA_PUBLIC_ASSETS.contains(path)) return true;
        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        // If a principal was already injected (e.g. by a test harness), pass through immediately.
        if (request.getAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE) != null) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean isMcp = path.startsWith("/mcp");
        boolean isHooks = path.startsWith("/hooks");
        boolean isApi = path.startsWith("/api/");
        boolean isVistierie = path.startsWith("/vistierie");
        // Peer sync authenticates with a bearer token (no browser session); defer to
        // AuthFilter like /mcp and /hooks instead of redirecting the peer to /login.
        boolean isSync = path.startsWith("/sync");
        // /admin serves two callers: browsers (session cookie; sessionless requests are
        // redirected to /login below) and CLI/scripts presenting a bearer token
        // (e.g. connect-peers.sh -> /admin/peers). Bearer requests defer to AuthFilter,
        // which validates the token or 401s — never an unauthenticated passthrough.
        boolean isAdminBearer = path.startsWith("/admin")
                && request.getHeader("Authorization") != null;

        // Machine paths are none of this filter's business — they authenticate with a
        // bearer token in AuthFilter. Not resolving a human principal here is what makes
        // /mcp reject session cookies.
        if (isMcp || isHooks || isVistierie || isSync || isAdminBearer) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<AuthPrincipal> principal = humanPrincipalResolver.resolve(request);
        if (principal.isPresent()) {
            // Mirror of AuthFilter:162-165, which only guards the bearer path: a
            // realm-scoped principal may only use /api/tools/call, where ToolCallDispatcher
            // enforces the ACL. /api/gui/stream and /api/attachments have no realm filter
            // at all, so anything else is denied.
            if (principal.get().isRealmScoped() && !path.equals("/api/tools/call")) {
                logDenial(request, HttpServletResponse.SC_FORBIDDEN);
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
            request.setAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE, principal.get());
            filterChain.doFilter(request, response);
            return;
        }

        if (isApi) {
            logDenial(request, HttpServletResponse.SC_UNAUTHORIZED);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        } else if (accessProperties.isEnabled()) {
            // No /login exists in Access mode — a redirect would 404 or fall through to
            // the SPA shell. Happens on direct-origin access that bypasses the tunnel.
            logDenial(request, HttpServletResponse.SC_FORBIDDEN);
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
        } else {
            logDenial(request, HttpServletResponse.SC_MOVED_TEMPORARILY);
            response.sendRedirect(request.getContextPath() + "/login");
        }
    }

    /**
     * One WARN per denial, carrying the discriminator an operator needs to tell the three
     * causes of a failed human login apart without guessing: whether the Cloudflare Access
     * header was present at all (an absent header means the request never carried a
     * credential; combined with the resolver's own log lines for an invalid or unmapped
     * JWT, each cause now has exactly one distinguishable signature). Every occurrence is a
     * human being turned away — worth seeing, and its volume is bounded by the failure
     * itself. Logs only method, path and status, never query strings, headers or bodies —
     * the path is already attacker-controlled input reaching the log elsewhere in this
     * codebase.
     */
    private void logDenial(HttpServletRequest request, int status) {
        boolean headerPresent = request.getHeader(AccessJwtResolver.HEADER) != null;
        log.warn("Human auth denied: {} {} -> {} (access-jwt header {})",
                request.getMethod(),
                request.getRequestURI().substring(request.getContextPath().length()),
                status,
                headerPresent ? "present" : "absent");
    }
}
