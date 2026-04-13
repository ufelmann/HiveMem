package com.hivemem.tools.write;

import com.fasterxml.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.write.WriteArgumentParser;
import com.hivemem.write.WriteToolService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Order(26)
public class LinkReferenceToolHandler implements ToolHandler {

    private final WriteToolService writeToolService;

    public LinkReferenceToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
    }

    @Override
    public String name() {
        return "hivemem_link_reference";
    }

    @Override
    public String description() {
        return "Link a reference to a drawer.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        UUID drawerId = WriteArgumentParser.requiredUuid(arguments, "drawer_id");
        UUID referenceId = WriteArgumentParser.requiredUuid(arguments, "reference_id");
        String relation = WriteArgumentParser.optionalText(arguments, "relation");
        if (relation == null) {
            relation = "source";
        }
        return writeToolService.linkReference(drawerId, referenceId, relation);
    }
}
