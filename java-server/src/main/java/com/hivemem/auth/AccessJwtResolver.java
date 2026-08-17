package com.hivemem.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Validates the Cf-Access-Jwt-Assertion header against the team's JWKS and maps the
 * email claim onto an api_tokens row.
 *
 * <p>The header is worthless without signature verification: anything that reaches the
 * origin directly (the LAN address, bypassing the tunnel) could otherwise set it and
 * become admin. RS256 is pinned so alg:none and HMAC key-confusion cannot apply. The
 * issuer is also pinned (unlike Dracul's CloudflareAccessFilter, which only checks aud) —
 * without it a JWT correctly signed by an unrelated Access team using the same JWKS
 * endpoint pattern could still pass.
 */
public class AccessJwtResolver implements HumanPrincipalResolver {

    private static final Logger log = LoggerFactory.getLogger(AccessJwtResolver.class);
    /**
     * The header Cloudflare Access injects. Public because callers outside this class must
     * agree on it — notably the OAuth consent 403, which logs whether it was present.
     * Renaming it in one place must not silently make that log line lie.
     */
    public static final String HEADER = "Cf-Access-Jwt-Assertion";

    private final ConfigurableJWTProcessor<SecurityContext> processor;
    private final TokenService tokenService;

    public AccessJwtResolver(AccessProperties props, TokenService tokenService) {
        this(jwksFromTeamDomain(props), props.getTeamDomain(), props.getAudience(), tokenService);
    }

    /** Test seam: inject a fixed public key instead of fetching a remote JWKS. */
    static AccessJwtResolver forTesting(String teamDomain, String audience, JWK publicKey,
                                        TokenService tokenService) {
        return new AccessJwtResolver(new ImmutableJWKSet<>(new JWKSet(publicKey)),
                teamDomain, audience, tokenService);
    }

    private AccessJwtResolver(JWKSource<SecurityContext> jwks, String teamDomain, String audience,
                              TokenService tokenService) {
        this.tokenService = tokenService;
        this.processor = new DefaultJWTProcessor<>();
        this.processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwks));
        DefaultJWTClaimsVerifier<SecurityContext> claimsVerifier = new DefaultJWTClaimsVerifier<>(
                audience,
                new JWTClaimsSet.Builder().issuer(teamDomain).build(),
                Set.of("email", "exp"));
        // Tolerate a small clock drift between the Cloudflare edge and this origin host
        // (NTP skew, typically low single-digit seconds) so a token doesn't get rejected
        // in the last second of its life just because our clock runs slightly ahead.
        // Access tokens are long-lived (minutes to hours), so 30s is negligible.
        claimsVerifier.setMaxClockSkew(30);
        this.processor.setJWTClaimsSetVerifier(claimsVerifier);
    }

    private static JWKSource<SecurityContext> jwksFromTeamDomain(AccessProperties props) {
        URI certs = URI.create(props.getTeamDomain() + "/cdn-cgi/access/certs");
        try {
            return JWKSourceBuilder.<SecurityContext>create(certs.toURL())
                    .cache(props.getJwksCacheTtl().toMillis(), 30_000)
                    .refreshAheadCache(true)
                    .build();
        } catch (MalformedURLException e) {
            // A bad team-domain is a fatal misconfiguration — fail startup, don't silently
            // authenticate nobody.
            throw new IllegalStateException(
                    "hivemem.access.team-domain does not form a valid JWKS URL: "
                            + props.getTeamDomain(), e);
        }
    }

    @Override
    public Optional<AuthPrincipal> resolve(HttpServletRequest request) {
        String jwt = request.getHeader(HEADER);
        if (jwt == null || jwt.isBlank()) return Optional.empty();
        try {
            JWTClaimsSet claims = processor.process(jwt, null);
            String email = claims.getStringClaim("email");
            if (email == null || email.isBlank()) return Optional.empty();
            // Belt-and-suspenders: TokenService#findByEmail already matches case-insensitively
            // in the DB; normalizing here too costs nothing and keeps behavior obvious to a
            // reader who only sees this call site.
            String normalizedEmail = email.toLowerCase(Locale.ROOT);
            Optional<AuthPrincipal> principal = tokenService.findByEmail(normalizedEmail);
            if (principal.isEmpty()) {
                // Name the (masked) identity so an operator can tell "logged in as the wrong
                // identity" from "mapping missing" — the whole point of this line — without
                // putting a full human email address in the log. AuthorizationControllerSplitHostTest
                // (forbiddenBranchLogsJwtPresentWhenEmailIsUnknownAndNeverLogsTheEmail,
                // AuthorizationControllerSplitHostTest.java:212) pins a codebase-wide invariant
                // that a human's email must never reach the log; masking is how this line
                // keeps its diagnostic value (which of several mapped identities, by domain and
                // shape) without violating it.
                String maskedEmail = maskEmail(normalizedEmail);
                log.warn("Access JWT verified for {} (masked) but no live api_tokens row maps it "
                                + "(never mapped, revoked, or expired) - denying. "
                                + "Fix: hivemem-token set-email <name> <the email shown by Access>",
                        maskedEmail);
            }
            return principal;
        } catch (Exception e) {
            log.warn("Rejected Access JWT: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Masks an email address for logging: keeps the first and last character of the local
     * part and the domain (including {@code @}) intact, replaces everything in between with a
     * fixed-length {@code ***} — never the true length of what's hidden, so the mask itself
     * gives no length signal. Never throws: a logging helper that can fail would turn a clean
     * {@code 401}/{@code 403} into a {@code 500}, so every edge case degrades to a safe,
     * non-identifying string instead.
     *
     * <p>Local parts of one or two characters end up fully visible (e.g. {@code bo} ->
     * {@code b***o}) — that's the same information the full address already carries, not new
     * exposure: the rule never reveals a character the original address didn't already show at
     * that position. A one-character local part is shown once, not doubled as first-and-last,
     * so the mask doesn't imply a length the address doesn't have.
     *
     * <p>Package-private (not private) only so the unit test can call it directly without
     * reflection; still an implementation detail, not part of any public API.
     */
    static String maskEmail(String email) {
        if (email == null || email.isBlank()) return "(blank)";
        int at = email.indexOf('@');
        if (at < 0) return maskLocalPart(email);
        String local = email.substring(0, at);
        String domainWithAt = email.substring(at);
        return maskLocalPart(local) + domainWithAt;
    }

    private static String maskLocalPart(String local) {
        if (local.isEmpty()) return "***";
        if (local.length() == 1) return local + "***";
        return local.charAt(0) + "***" + local.charAt(local.length() - 1);
    }
}
