package com.hivemem.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;

public interface ToolHandler {

    String name();

    String description();

    Object call(AuthPrincipal principal, JsonNode arguments);
}
