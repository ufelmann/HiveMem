package com.hivemem.mcp;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;

import java.util.Map;

public interface ToolHandler {

    String name();

    String description();

    /**
     * JSON Schema describing the arguments this tool accepts. Surfaced to MCP
     * clients via {@code tools/list} so they know parameter names and types up
     * front instead of guessing.
     *
     * <p>Default implementation returns an empty schema for handlers that
     * accept no arguments. Handlers with arguments should override this and
     * return a schema built via {@link ToolInputSchema}.
     */
    default Map<String, Object> inputSchema() {
        return ToolInputSchema.empty();
    }

    Object call(AuthPrincipal principal, JsonNode arguments);
}
