package com.hivemem.tools.write;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.write.WriteArgumentParser;
import com.hivemem.write.WriteToolService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(24)
public class UpdateIdentityToolHandler implements ToolHandler {

    private final WriteToolService writeToolService;

    public UpdateIdentityToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
    }

    @Override
    public String name() {
        return "hivemem_update_identity";
    }

    @Override
    public String description() {
        return "Upsert an identity record.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String key = WriteArgumentParser.requiredText(arguments, "key");
        String content = WriteArgumentParser.requiredText(arguments, "content");
        return writeToolService.updateIdentity(key, content);
    }
}
