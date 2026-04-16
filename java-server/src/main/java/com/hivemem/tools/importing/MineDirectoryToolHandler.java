package com.hivemem.tools.importing;

import com.fasterxml.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.write.WriteArgumentParser;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(20)
public class MineDirectoryToolHandler implements ToolHandler {

    private final ImportToolService importToolService;

    public MineDirectoryToolHandler(ImportToolService importToolService) {
        this.importToolService = importToolService;
    }

    @Override
    public String name() {
        return "hivemem_mine_directory";
    }

    @Override
    public String description() {
        return "Import supported files from a directory as drawers.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        return importToolService.mineDirectory(
                principal,
                WriteArgumentParser.requiredText(arguments, "dir_path"),
                WriteArgumentParser.optionalText(arguments, "wing"),
                WriteArgumentParser.optionalText(arguments, "hall"),
                WriteArgumentParser.optionalText(arguments, "room"),
                WriteArgumentParser.optionalTextList(arguments, "extensions")
        );
    }
}
