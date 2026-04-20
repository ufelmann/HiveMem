package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Order(5)
public class ListRealmsToolHandler implements ToolHandler {

    private final ReadToolService readToolService;

    public ListRealmsToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() {
        return "hivemem_list_realms";
    }

    @Override
    public String description() {
        return "Realms with counts, or signals of a specific realm when 'realm' is provided.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .optionalString("realm", "Realm name; omit to list all realms with counts")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String realm = null;
        if (arguments != null && arguments.hasNonNull("realm")) {
            String value = arguments.get("realm").asText();
            if (!value.isBlank()) {
                realm = value;
            }
        }
        if (realm == null) {
            return readToolService.listRealms();
        }
        return readToolService.listSignals(realm);
    }
}
