package com.hivemem.mcp;

import tools.jackson.databind.JsonNode;

public record McpRequest(
        String jsonrpc,
        Object id,
        String method,
        JsonNode params
) {
}
