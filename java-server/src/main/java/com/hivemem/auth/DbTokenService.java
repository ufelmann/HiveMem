package com.hivemem.auth;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

@Service
public class DbTokenService implements TokenService {

    private final DSLContext dslContext;

    public DbTokenService(DSLContext dslContext) {
        this.dslContext = dslContext;
    }

    @Override
    public Optional<AuthPrincipal> validateToken(String token) {
        String tokenHash = sha256(token);
        Record row = dslContext.fetchOne("""
                SELECT name, role
                FROM api_tokens
                WHERE token_hash = ?
                  AND revoked_at IS NULL
                  AND (expires_at IS NULL OR expires_at > now())
                """, tokenHash);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new AuthPrincipal(
                row.get("name", String.class),
                AuthRole.valueOf(row.get("role", String.class).toUpperCase(Locale.ROOT))
        ));
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
