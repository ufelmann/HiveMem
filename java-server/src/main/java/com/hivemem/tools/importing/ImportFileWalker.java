package com.hivemem.tools.importing;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

@Component
public class ImportFileWalker {

    private static final List<String> DEFAULT_EXTENSIONS = List.of(".md", ".txt", ".yaml", ".yml");

    public List<Path> walk(Path directory, List<String> extensions) {
        Set<String> normalizedExtensions = normalizeExtensions(extensions);
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> normalizedExtensions.contains(extensionOf(path)))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to read import directory", e);
        }
    }

    private static Set<String> normalizeExtensions(List<String> extensions) {
        List<String> source = (extensions == null || extensions.isEmpty()) ? DEFAULT_EXTENSIONS : extensions;
        return source.stream()
                .map(ImportFileWalker::normalizeExtension)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String normalizeExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            throw new IllegalArgumentException("Invalid extensions");
        }
        String normalized = extension.toLowerCase(Locale.ROOT);
        return normalized.startsWith(".") ? normalized : "." + normalized;
    }

    private static String extensionOf(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot < 0) {
            return "";
        }
        return fileName.substring(lastDot);
    }
}
