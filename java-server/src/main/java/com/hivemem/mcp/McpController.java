package com.hivemem.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.hivemem.auth.AuthFilter;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.ToolPermissionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<McpResponse> handle(@RequestBody McpRequest request, HttpServletRequest servletRequest) {
        AuthPrincipal principal = (AuthPrincipal) servletRequest.getAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE);
        if (principal == null) {
            return ResponseEntity.badRequest().body(
                    McpResponse.invalidParams(request.id(), "Missing authenticated principal"));
        }

        String method = request.method();
        if (method == null || method.isBlank()) {
            return ResponseEntity.ok(McpResponse.methodNotFound(request.id(), method));
        }

        return switch (method) {
            case "tools/list" -> ResponseEntity.ok(McpResponse.success(
                    request.id(),
                    Map.of("tools", toolRegistry.visibleTools(principal.role(), toolPermissionService))
            ));
            case "tools/call" -> handleToolCall(request, principal);
            default -> ResponseEntity.ok(McpResponse.methodNotFound(request.id(), method));
        };
    }

    private ResponseEntity<McpResponse> handleToolCall(McpRequest request, AuthPrincipal principal) {
        JsonNode params = request.params();
        if (params == null || !params.hasNonNull("name")) {
            return ResponseEntity.badRequest().body(
                    McpResponse.invalidParams(request.id(), "Missing tool name"));
        }

        String toolName = params.get("name").asText();
        if (toolName.isBlank()) {
            return ResponseEntity.badRequest().body(
                    McpResponse.invalidParams(request.id(), "Missing tool name"));
        }
        if (!toolPermissionService.isAllowed(principal.role(), toolName)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    McpResponse.forbidden(request.id(), toolName));
        }

        return toolRegistry.resolve(toolName)
                .map(handler -> {
                    try {
                        return ResponseEntity.ok(
                                McpResponse.toolResult(request.id(), handler.call(principal, params.path("arguments"))));
                    } catch (IllegalArgumentException e) {
                        return ResponseEntity.badRequest().body(
                                McpResponse.invalidParams(request.id(), e.getMessage()));
                    }
                })
                .orElseGet(() -> ResponseEntity.ok(McpResponse.toolNotFound(request.id(), toolName)));
    }
}
