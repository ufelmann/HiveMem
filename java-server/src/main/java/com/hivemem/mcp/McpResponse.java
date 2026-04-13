package com.hivemem.mcp;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;

public record McpResponse(
        String jsonrpc,
        Object id,
        Object result,
        McpError error
) {
    public static McpResponse success(Object id, Object result) {
        return new McpResponse("2.0", id, result, null);
    }

    public static McpResponse methodNotFound(Object id, String method) {
        return new McpResponse("2.0", id, null, new McpError(-32601, "Method not found: " + method, null));
    }

    public static McpResponse invalidParams(Object id, String message) {
        return new McpResponse("2.0", id, null, new McpError(-32602, message, null));
    }

    public static McpResponse toolNotFound(Object id, String toolName) {
        return new McpResponse("2.0", id, null, new McpError(-32601, "Method not found: " + toolName, null));
    }

    public static McpResponse forbidden(Object id, String toolName) {
        return new McpResponse("2.0", id, null, new McpError(-32003, "Tool not permitted: " + toolName, null));
    }

    public static McpResponse toolResult(Object id, Object content) {
        List<Object> payload = new ArrayList<>(1);
        payload.add(content);
        return success(id, Map.of("content", payload));
    }

    public record McpError(int code, String message, Object data) {
    }
}

record McpTool(
        String name,
        String description,
        Map<String, Object> inputSchema
) {
    static McpTool of(String name, String description) {
        return new McpTool(
                name,
                description,
                Map.of(
                        "type", "object",
                        "properties", Map.of(),
                        "additionalProperties", Boolean.TRUE
                )
        );
    }
}
