package com.hivemem.auth;

import java.util.Optional;

public interface TokenService {

    Optional<AuthPrincipal> validateToken(String token);
}
