package com.hivemem.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.hivemem.auth.AuthFilter;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.ToolPermissionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class McpController {

    private final ToolRegistry toolRegistry;
    private final ToolPermissionService toolPermissionService;

    public McpController(ToolRegistry toolRegistry, ToolPermissionService toolPermissionService) {
        this.toolRegistry = toolRegistry;
        this.toolPermissionService = toolPermissionService;
    }

    @PostMapping(value = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public McpResponse handle(@RequestBody McpRequest request, HttpServletRequest servletRequest) {
        AuthPrincipal principal = (AuthPrincipal) servletRequest.getAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE);
        if (principal == null) {
            return McpResponse.invalidParams(request.id(), "Missing authenticated principal");
        }

        String method = request.method();
        if (method == null || method.isBlank()) {
            return McpResponse.methodNotFound(request.id(), method);
        }

        return switch (method) {
            case "tools/list" -> McpResponse.success(
                    request.id(),
                    Map.of("tools", toolRegistry.visibleTools(principal.role(), toolPermissionService))
            );
            case "tools/call" -> handleToolCall(request, principal);
            default -> McpResponse.methodNotFound(request.id(), method);
        };
    }

    private McpResponse handleToolCall(McpRequest request, AuthPrincipal principal) {
        JsonNode params = request.params();
        if (params == null || !params.hasNonNull("name")) {
            return McpResponse.invalidParams(request.id(), "Missing tool name");
        }

        String toolName = params.get("name").asText();
        if (toolName.isBlank()) {
            return McpResponse.invalidParams(request.id(), "Missing tool name");
        }
        if (!toolPermissionService.isAllowed(principal.role(), toolName)) {
            return McpResponse.forbidden(request.id(), toolName);
        }

        return toolRegistry.resolve(toolName)
                .map(handler -> {
                    try {
                        return McpResponse.toolResult(request.id(), handler.call(principal, params.path("arguments")));
                    } catch (IllegalArgumentException e) {
                        return McpResponse.invalidParams(request.id(), e.getMessage());
                    }
                })
                .orElseGet(() -> McpResponse.toolNotFound(request.id(), toolName));
    }
}
