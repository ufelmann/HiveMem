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
@Order(33)
public class ReviseDrawerToolHandler implements ToolHandler {

    private final WriteToolService writeToolService;

    public ReviseDrawerToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
    }

    @Override
    public String name() {
        return "hivemem_revise_drawer";
    }

    @Override
    public String description() {
        return "Revise a drawer by closing the current version and inserting a new one.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        UUID oldId = WriteArgumentParser.requiredUuid(arguments, "old_id");
        String newContent = WriteArgumentParser.requiredText(arguments, "new_content");
        String newSummary = WriteArgumentParser.optionalText(arguments, "new_summary");
        return writeToolService.reviseDrawer(principal, oldId, newContent, newSummary);
    }
}
