package com.hivemem.write;

import com.hivemem.embedding.EmbeddingMigrationService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AdminToolService {

    private final AdminToolRepository adminToolRepository;
    private final EmbeddingMigrationService embeddingMigrationService;

    public AdminToolService(AdminToolRepository adminToolRepository,
                            EmbeddingMigrationService embeddingMigrationService) {
        this.adminToolRepository = adminToolRepository;
        this.embeddingMigrationService = embeddingMigrationService;
    }

    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>(adminToolRepository.health());
        if (embeddingMigrationService.isReencodingActive()) {
            result.put("reencoding", Map.of(
                    "active", true,
                    "progress", embeddingMigrationService.getProgress().orElse("unknown")
            ));
        }
        return result;
    }

    public Map<String, Object> logAccess(UUID cellId, UUID factId, String accessedBy) {
        adminToolRepository.logAccess(cellId, factId, accessedBy);
        return Map.of("logged", true);
    }

    public Map<String, Object> refreshPopularity() {
        return Map.of(
                "refreshed", true,
                "cell_count", adminToolRepository.refreshPopularity()
        );
    }
}
