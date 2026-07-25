package com.hivemem.oauth;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;

/**
 * Configuration for the OAuth 2.0 authorization server that exposes HiveMem
 * as an MCP Custom Connector to clients like Claude.ai or ChatGPT.
 */
@Component
@ConfigurationProperties(prefix = "hivemem.oauth")
public class OAuthProperties {

    /** Whether OAuth endpoints are enabled. Disabled by default until a public HTTPS issuer is configured. */
    private boolean enabled = false;

    /**
     * Public HTTPS issuer URL (e.g. {@code https://hivemem.example.com}). Must match
     * the URL clients reach the discovery endpoints at — used as the {@code iss}
     * value in token claims and discovery metadata.
     */
    private String issuer = "";

    /** Lifetime of issued access tokens. Default: 1 hour. */
    private Duration accessTokenTtl = Duration.ofHours(1);

    /** Lifetime of issued refresh tokens. Default: 30 days. */
    private Duration refreshTokenTtl = Duration.ofDays(30);

    /** Lifetime of authorization codes between issue and exchange. Default: 10 minutes. */
    private Duration authorizationCodeTtl = Duration.ofMinutes(10);

    /** Whether to allow Dynamic Client Registration (RFC 7591). Required by Claude.ai Custom Connector flow. */
    private boolean dynamicClientRegistrationEnabled = true;

    /**
     * Origin of the host that serves the browser consent page, when it differs from the
     * issuer — e.g. {@code https://gui.example.com}. Used only by
     * {@code AuthorizationController}: a {@code GET /oauth/authorize} that arrives on a
     * different host in Access mode without a resolvable principal is redirected here
     * instead of being refused, so the consent step can run behind Cloudflare Access while
     * the issuer host stays open for machine callers.
     *
     * <p>Empty (the default) disables the redirect entirely — single-host deployments
     * behave exactly as before.
     */
    private String authorizeRedirectBaseUrl = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }

    public Duration getAccessTokenTtl() { return accessTokenTtl; }
    public void setAccessTokenTtl(Duration accessTokenTtl) { this.accessTokenTtl = accessTokenTtl; }

    public Duration getRefreshTokenTtl() { return refreshTokenTtl; }
    public void setRefreshTokenTtl(Duration refreshTokenTtl) { this.refreshTokenTtl = refreshTokenTtl; }

    public Duration getAuthorizationCodeTtl() { return authorizationCodeTtl; }
    public void setAuthorizationCodeTtl(Duration authorizationCodeTtl) { this.authorizationCodeTtl = authorizationCodeTtl; }

    public boolean isDynamicClientRegistrationEnabled() { return dynamicClientRegistrationEnabled; }
    public void setDynamicClientRegistrationEnabled(boolean dynamicClientRegistrationEnabled) {
        this.dynamicClientRegistrationEnabled = dynamicClientRegistrationEnabled;
    }

    public String getAuthorizeRedirectBaseUrl() { return authorizeRedirectBaseUrl; }
    public void setAuthorizeRedirectBaseUrl(String authorizeRedirectBaseUrl) {
        this.authorizeRedirectBaseUrl = authorizeRedirectBaseUrl;
    }

    /**
     * Fail-closed validation, mirroring {@code HumanAuthResolverConfig}'s blank-team-domain
     * check: a misconfigured value must abort startup rather than silently sending the
     * consent step somewhere unintended. Normalises a trailing slash away so the caller can
     * concatenate a path without producing a double slash.
     */
    @PostConstruct
    void validateAuthorizeRedirectBaseUrl() {
        if (authorizeRedirectBaseUrl == null || authorizeRedirectBaseUrl.isBlank()) {
            authorizeRedirectBaseUrl = "";
            return;
        }
        String original = authorizeRedirectBaseUrl;
        String value = authorizeRedirectBaseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(
                    "hivemem.oauth.authorize-redirect-base-url is not a valid URI: " + original, e);
        }
        boolean originOnly = "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null && !uri.getHost().isBlank()
                && uri.getRawUserInfo() == null
                && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null;
        if (!originOnly) {
            throw new IllegalStateException(
                    "hivemem.oauth.authorize-redirect-base-url must be an absolute https origin "
                            + "with no userinfo, path, query or fragment (e.g. https://gui.example.com), but was: "
                            + original);
        }
        authorizeRedirectBaseUrl = value;
    }
}
