package com.hivemem.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.hivemem.auth.AuthFilter;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.ToolPermissionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private final ToolRegistry toolRegistry;
    private final ToolPermissionService toolPermissionService;

    public McpController(ToolRegistry toolRegistry, ToolPermissionService toolPermissionService) {
        this.toolRegistry = toolRegistry;
        this.toolPermissionService = toolPermissionService;
    }

    /**
     * SSE endpoint for server-initiated messages (MCP Streamable HTTP spec).
     * We don't send server-initiated messages, but Claude Code requires this
     * endpoint to exist and return 200 with text/event-stream. The emitter
     * stays open until the client disconnects.
     */
    @GetMapping(value = "/mcp", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (java.io.IOException ignored) {
            // client already disconnected
        }
        emitter.onTimeout(emitter::complete);
        return emitter;
    }

    /**
     * Session termination (MCP Streamable HTTP spec). No-op for stateless server.
     */
    @DeleteMapping(value = "/mcp")
    public ResponseEntity<Void> deleteSession() {
        return ResponseEntity.ok().build();
    }

    @PostMapping(value = "/mcp")
    public ResponseEntity<?> handle(@RequestBody McpRequest request, HttpServletRequest servletRequest) {
        log.info("MCP request: method={} id={} accept={} content-type={}",
                request.method(), request.id(),
                servletRequest.getHeader("Accept"),
                servletRequest.getHeader("Content-Type"));
        AuthPrincipal principal = (AuthPrincipal) servletRequest.getAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE);
        if (principal == null) {
            return ResponseEntity.badRequest().body(
                    McpResponse.invalidParams(request.id(), "Missing authenticated principal"));
        }

        String method = request.method();
        if (method == null || method.isBlank()) {
            return ResponseEntity.ok(McpResponse.methodNotFound(request.id(), method));
        }

        // Notifications (no id) get 202 Accepted with no body per MCP Streamable HTTP spec.
        if (method.startsWith("notifications/")) {
            return ResponseEntity.accepted().build();
        }

        return switch (method) {
            case "initialize" -> ResponseEntity.ok()
                    .header(SESSION_HEADER, UUID.randomUUID().toString())
                    .body(McpResponse.success(
                            request.id(),
                            Map.of(
                                    "protocolVersion", "2025-03-26",
                                    "capabilities", Map.of("tools", Map.of()),
                                    "serverInfo", Map.of("name", "hivemem", "version", "3.0.2")
                            )
                    ));
            case "ping" -> ResponseEntity.ok(McpResponse.success(request.id(), Map.of()));
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
