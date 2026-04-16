package com.hivemem.mcp;

import com.hivemem.auth.AuthRole;
import com.hivemem.auth.ToolPermissionService;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ToolRegistry {

    private final List<ToolHandler> handlers;

    public ToolRegistry(List<ToolHandler> handlers) {
        if (handlers == null) {
            this.handlers = List.of();
            return;
        }
        java.util.ArrayList<ToolHandler> ordered = new java.util.ArrayList<>(handlers);
        AnnotationAwareOrderComparator.sort(ordered);
        this.handlers = List.copyOf(ordered);
    }

    public List<McpTool> visibleTools(AuthRole role, ToolPermissionService permissionService) {
        Set<String> allowed = permissionService.allowedTools(role);
        return handlers.stream()
                .filter(handler -> allowed.contains(handler.name()))
                .map(handler -> McpTool.of(handler.name(), handler.description()))
                .collect(Collectors.toUnmodifiableList());
    }

    public Optional<ToolHandler> resolve(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        return handlers.stream()
                .filter(handler -> handler.name().equals(toolName))
                .findFirst();
    }
}
