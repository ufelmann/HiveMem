package com.hivemem.security;

import org.springframework.stereotype.Component;

import java.io.IOException;
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
                .map(ImportPathValidator::normalizeAllowedRoot)
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
        Path effectivePath = resolveEffectivePath(normalized);
        boolean allowed = allowedRoots.stream().anyMatch(effectivePath::startsWith);
        if (!allowed) {
            throw new IllegalArgumentException("Path is outside allowed import directories");
        }
        return java.nio.file.Files.exists(normalized) ? effectivePath : normalized;
    }

    private static Path normalizeAllowedRoot(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        try {
            return normalized.toRealPath();
        } catch (IOException ignored) {
            return normalized;
        }
    }

    private static Path resolveEffectivePath(Path normalized) {
        try {
            if (java.nio.file.Files.exists(normalized)) {
                return normalized.toRealPath();
            }
            Path parent = normalized.getParent();
            if (parent == null) {
                throw new IllegalArgumentException("Path is outside allowed import directories");
            }
            Path realParent = parent.toRealPath();
            return realParent.resolve(normalized.getFileName()).normalize();
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to validate import path", e);
        }
    }
}
