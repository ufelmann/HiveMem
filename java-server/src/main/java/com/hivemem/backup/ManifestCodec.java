package com.hivemem.backup;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class ManifestCodec {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private ManifestCodec() {}

    public static String toJson(Manifest m) {
        return MAPPER.writeValueAsString(m);
    }

    public static Manifest fromJson(String json) {
        try {
            return MAPPER.readValue(json, Manifest.class);
        } catch (JacksonException e) {
            throw new RuntimeException("Failed to parse manifest: " + e.getMessage(), e);
        }
    }

    public static void write(Manifest m, OutputStream out) throws IOException {
        try {
            MAPPER.writeValue(out, m);
        } catch (JacksonException e) {
            throw new IOException("Failed to serialize manifest", e);
        }
    }

    public static Manifest read(InputStream in) throws IOException {
        try {
            return MAPPER.readValue(in, Manifest.class);
        } catch (JacksonException e) {
            throw new IOException("Failed to parse manifest: " + e.getMessage(), e);
        }
    }
}
