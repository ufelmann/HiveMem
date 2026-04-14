package com.hivemem.security;

import org.junit.jupiter.api.Test;

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
    void allowsPathInsideConfiguredSafeDirectory() {
        Path safeDirectory = Path.of("/tmp/hivemem-safe");
        ImportPathValidator validator = new ImportPathValidator(List.of(safeDirectory));

        Path validated = validator.validate("/tmp/hivemem-safe/test.md");

        assertEquals(Path.of("/tmp/hivemem-safe/test.md"), validated);
    }

    @Test
    void defaultValidatorAllowsTmpDirectory() {
        Path validated = ImportPathValidator.defaultValidator().validate("/tmp/test.txt");

        assertEquals(Path.of("/tmp/test.txt"), validated);
    }
}
