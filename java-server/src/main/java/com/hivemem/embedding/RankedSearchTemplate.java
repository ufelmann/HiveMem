package com.hivemem.embedding;

import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

final class RankedSearchTemplate {

    private static final String RESOURCE_PATH = "db/templates/ranked_search.sql.tmpl";
    private static final String PLACEHOLDER = "{{DIM}}";

    private RankedSearchTemplate() {
    }

    static String render(int dimension) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be positive, got " + dimension);
        }
        String template = load();
        return template.replace(PLACEHOLDER, Integer.toString(dimension));
    }

    private static String load() {
        try (InputStream in = new ClassPathResource(RESOURCE_PATH).getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + RESOURCE_PATH, e);
        }
    }
}
