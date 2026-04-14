package com.hivemem.tools.importing;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.security.ImportPathValidator;
import com.hivemem.write.WriteToolService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ImportToolService {

    private final ImportPathValidator pathValidator;
    private final ImportFileWalker fileWalker;
    private final WriteToolService writeToolService;

    public ImportToolService(
            ImportPathValidator pathValidator,
            ImportFileWalker fileWalker,
            WriteToolService writeToolService
    ) {
        this.pathValidator = pathValidator;
        this.fileWalker = fileWalker;
        this.writeToolService = writeToolService;
    }

    public Map<String, Object> mineFile(
            AuthPrincipal principal,
            String rawPath,
            String wing,
            String hall,
            String room
    ) {
        Path file = pathValidator.validate(rawPath);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Path is not a file");
        }
        return mineValidatedFile(principal, file, wing, hall, room);
    }

    public Map<String, Object> mineDirectory(
            AuthPrincipal principal,
            String rawPath,
            String wing,
            String hall,
            String room,
            List<String> extensions
    ) {
        Path directory = pathValidator.validate(rawPath);
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Path is not a directory");
        }
        List<Path> files = fileWalker.walk(directory, extensions);
        int drawersCreated = 0;
        List<String> errors = new ArrayList<>();
        for (Path file : files) {
            try {
                Map<String, Object> result = mineValidatedFile(principal, file, wing, hall, room);
                drawersCreated += ((Number) result.get("drawers_created")).intValue();
            } catch (IllegalArgumentException e) {
                errors.add(file + ": " + e.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("files_processed", files.size());
        result.put("drawers_created", drawersCreated);
        result.put("errors", List.copyOf(errors));
        return result;
    }

    private Map<String, Object> mineValidatedFile(
            AuthPrincipal principal,
            Path file,
            String wing,
            String hall,
            String room
    ) {
        String content = readContent(file);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file", file.toString());
        if (content.isBlank()) {
            result.put("drawers_created", 0);
            result.put("drawer_id", null);
            return result;
        }

        Map<String, Object> drawer = writeToolService.addDrawer(
                principal,
                content,
                wing,
                hall,
                room,
                file.toString(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        result.put("drawers_created", 1);
        result.put("drawer_id", drawer.get("id"));
        return result;
    }

    private static String readContent(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to read import file", e);
        }
    }
}
