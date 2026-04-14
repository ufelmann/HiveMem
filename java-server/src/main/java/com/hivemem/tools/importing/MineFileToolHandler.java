package com.hivemem.tools.importing;

import com.fasterxml.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.write.WriteArgumentParser;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(19)
public class MineFileToolHandler implements ToolHandler {

    private final ImportToolService importToolService;

    public MineFileToolHandler(ImportToolService importToolService) {
        this.importToolService = importToolService;
    }

    @Override
    public String name() {
        return "hivemem_mine_file";
    }

    @Override
    public String description() {
        return "Import a single supported file as a drawer.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        return importToolService.mineFile(
                principal,
                WriteArgumentParser.requiredText(arguments, "path"),
                WriteArgumentParser.optionalText(arguments, "wing"),
                WriteArgumentParser.optionalText(arguments, "hall"),
                WriteArgumentParser.optionalText(arguments, "room")
        );
    }
}
