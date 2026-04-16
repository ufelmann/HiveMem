package com.hivemem.auth;

import java.util.Objects;

public record AuthPrincipal(String name, AuthRole role) {

    public AuthPrincipal {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(role, "role");
    }
}
