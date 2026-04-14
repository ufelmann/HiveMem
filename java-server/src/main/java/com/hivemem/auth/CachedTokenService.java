package com.hivemem.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.Optional;

public class CachedTokenService implements TokenService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final int CACHE_MAX_SIZE = 1000;

    private final DbTokenService delegate;
    private final Cache<String, Optional<AuthPrincipal>> cache;

    public CachedTokenService(DbTokenService delegate) {
        this.delegate = delegate;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(CACHE_TTL)
                .maximumSize(CACHE_MAX_SIZE)
                .build();
    }

    @Override
    public Optional<AuthPrincipal> validateToken(String token) {
        return cache.get(token, delegate::validateToken);
    }
}
