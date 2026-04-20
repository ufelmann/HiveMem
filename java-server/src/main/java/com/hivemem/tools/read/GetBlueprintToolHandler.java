package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(16)
public class GetBlueprintToolHandler implements ToolHandler {

    private final ReadToolService readToolService;

    public GetBlueprintToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() {
        return "hivemem_get_blueprint";
    }

    @Override
    public String description() {
        return "Active blueprints for a realm.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String realm = optionalText(arguments, "realm");
        return readToolService.getBlueprint(realm);
    }

    private static String optionalText(JsonNode arguments, String field) {
        if (arguments == null || !arguments.hasNonNull(field)) {
            return null;
        }
        String value = arguments.get(field).asText();
        return value.isBlank() ? null : value;
    }
}
