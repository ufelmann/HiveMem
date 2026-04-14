package com.hivemem.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PathSafetyTest {

    @Test
    void rejectsSensitiveSystemPath() {
        ImportPathValidator validator = new ImportPathValidator(List.of(
                Path.of("/data/imports"),
                Path.of("/tmp")
        ));

        assertThrows(IllegalArgumentException.class, () -> validator.validate("/etc/passwd"));
    }

    @Test
    void rejectsDotDotTraversalOutsideAllowedDirectory() {
        ImportPathValidator validator = new ImportPathValidator(List.of(
                Path.of("/data/imports"),
                Path.of("/tmp")
        ));

        assertThrows(IllegalArgumentException.class, () -> validator.validate("/data/imports/../../etc/passwd"));
    }

    @Test
    void allowsPathInsideConfiguredSafeDirectory() throws IOException {
        Path safeDirectory = Files.createTempDirectory("hivemem-safe");
        ImportPathValidator validator = new ImportPathValidator(List.of(safeDirectory));
        Path file = safeDirectory.resolve("test.md");

        try {
            Path validated = validator.validate(file.toString());
            assertEquals(file, validated);
        } finally {
            Files.deleteIfExists(safeDirectory);
        }
    }

    @Test
    void defaultValidatorAllowsTmpDirectory() {
        Path validated = ImportPathValidator.defaultValidator().validate("/tmp/test.txt");

        assertEquals(Path.of("/tmp/test.txt"), validated);
    }

    @Test
    void rejectsSymlinkThatEscapesAllowedRoot() throws IOException {
        Path allowedRoot = Files.createTempDirectory("hivemem-safe-root");
        Path outsideRoot = Files.createTempDirectory("hivemem-outside-root");
        Path target = Files.writeString(outsideRoot.resolve("secret.md"), "classified");
        Path symlink = allowedRoot.resolve("escape.md");
        Files.createSymbolicLink(symlink, target);

        try {
            ImportPathValidator validator = new ImportPathValidator(List.of(allowedRoot));

            assertThrows(IllegalArgumentException.class, () -> validator.validate(symlink.toString()));
        } finally {
            Files.deleteIfExists(symlink);
            Files.deleteIfExists(target);
            Files.deleteIfExists(allowedRoot);
            Files.deleteIfExists(outsideRoot);
        }
    }
}
