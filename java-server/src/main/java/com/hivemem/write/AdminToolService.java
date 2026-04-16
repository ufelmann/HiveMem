package com.hivemem.write;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
public class AdminToolService {

    private final AdminToolRepository adminToolRepository;

    public AdminToolService(AdminToolRepository adminToolRepository) {
        this.adminToolRepository = adminToolRepository;
    }

    public Map<String, Object> health() {
        return adminToolRepository.health();
    }

    public Map<String, Object> logAccess(UUID drawerId, UUID factId, String accessedBy) {
        adminToolRepository.logAccess(drawerId, factId, accessedBy);
        return Map.of("logged", true);
    }

    public Map<String, Object> refreshPopularity() {
        return Map.of(
                "refreshed", true,
                "drawer_count", adminToolRepository.refreshPopularity()
        );
    }
}
