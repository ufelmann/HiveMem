package com.hivemem.security;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

@Component
public class ImportPathValidator {

    private static final List<Path> DEFAULT_ALLOWED_ROOTS = List.of(
            Path.of("/data/imports"),
            Path.of("/tmp")
    );

    private final List<Path> allowedRoots;

    public ImportPathValidator() {
        this(DEFAULT_ALLOWED_ROOTS);
    }

    public ImportPathValidator(List<Path> allowedRoots) {
        if (allowedRoots == null || allowedRoots.isEmpty()) {
            throw new IllegalArgumentException("At least one allowed import directory is required");
        }
        this.allowedRoots = allowedRoots.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
    }

    public static ImportPathValidator defaultValidator() {
        return new ImportPathValidator(DEFAULT_ALLOWED_ROOTS);
    }

    public Path validate(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("Missing path");
        }
        Path normalized = Path.of(rawPath).toAbsolutePath().normalize();
        boolean allowed = allowedRoots.stream().anyMatch(normalized::startsWith);
        if (!allowed) {
            throw new IllegalArgumentException("Path is outside allowed import directories");
        }
        return normalized;
    }
}
