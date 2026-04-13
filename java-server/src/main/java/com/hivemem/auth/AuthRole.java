package com.hivemem.auth;

import java.util.Locale;
import java.util.Objects;

public enum AuthRole {
    ADMIN,
    WRITER,
    READER,
    AGENT;

    public static AuthRole fromWireValue(String value) {
        Objects.requireNonNull(value, "value");
        return AuthRole.valueOf(value.toUpperCase(Locale.ROOT));
    }

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
