package com.hivemem.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AuthFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL_ATTRIBUTE = AuthPrincipal.class.getName();
    private static final String BEARER_PREFIX = "Bearer ";

    private final Optional<TokenService> tokenService;
    private final RateLimiter rateLimiter;

    public AuthFilter(Optional<TokenService> tokenService, RateLimiter rateLimiter) {
        this.tokenService = tokenService;
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        return !requestPath.startsWith("/mcp") && !requestPath.startsWith("/hooks")
                && !requestPath.startsWith("/sync") && !requestPath.startsWith("/admin")
                && !requestPath.startsWith("/api/attachments");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (request.getAttribute(PRINCIPAL_ATTRIBUTE) != null) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = request.getRemoteAddr();

        long retryAfter = rateLimiter.checkRateLimit(clientIp);
        if (retryAfter > 0) {
            response.setIntHeader("Retry-After", (int) retryAfter);
            response.sendError(429);
            return;
        }

        String authorization = request.getHeader("Authorization");
        if (authorization == null
                || authorization.length() < BEARER_PREFIX.length()
                || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            rateLimiter.recordFailure(clientIp);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            rateLimiter.recordFailure(clientIp);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        if (tokenService.isEmpty()) {
            rateLimiter.recordFailure(clientIp);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        Optional<AuthPrincipal> principal = tokenService.orElseThrow().validateToken(token);
        if (principal.isEmpty()) {
            rateLimiter.recordFailure(clientIp);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        rateLimiter.clearFailures(clientIp);
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal.get());
        filterChain.doFilter(request, response);
    }
}
