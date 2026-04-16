package com.hivemem.tools.write;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.write.WriteArgumentParser;
import com.hivemem.write.WriteToolService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Order(23)
public class ReviseFactToolHandler implements ToolHandler {

    private final WriteToolService writeToolService;

    public ReviseFactToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
    }

    @Override
    public String name() {
        return "hivemem_revise_fact";
    }

    @Override
    public String description() {
        return "Revise a fact by closing the current version and inserting a new one.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        UUID oldId = WriteArgumentParser.requiredUuid(arguments, "old_id");
        String newObject = WriteArgumentParser.requiredText(arguments, "new_object");
        return writeToolService.reviseFact(principal, oldId, newObject);
    }
}
