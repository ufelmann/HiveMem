package com.hivemem.tools.write;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.write.WriteArgumentParser;
import com.hivemem.write.WriteToolService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

@Component
@Order(18)
public class AddDrawerToolHandler implements ToolHandler {

    private final WriteToolService writeToolService;

    public AddDrawerToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
    }

    @Override
    public String name() {
        return "hivemem_add_drawer";
    }

    @Override
    public String description() {
        return "Create a new drawer with progressive layers and embedding.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String content = WriteArgumentParser.requiredText(arguments, "content");
        String wing = WriteArgumentParser.optionalText(arguments, "wing");
        String hall = WriteArgumentParser.optionalText(arguments, "hall");
        String room = WriteArgumentParser.optionalText(arguments, "room");
        String source = WriteArgumentParser.optionalText(arguments, "source");
        List<String> tags = WriteArgumentParser.optionalTextList(arguments, "tags");
        Integer importance = WriteArgumentParser.optionalInteger(arguments, "importance");
        String summary = WriteArgumentParser.optionalText(arguments, "summary");
        List<String> keyPoints = WriteArgumentParser.optionalTextList(arguments, "key_points");
        String insight = WriteArgumentParser.optionalText(arguments, "insight");
        String actionability = WriteArgumentParser.optionalText(arguments, "actionability");
        String status = WriteArgumentParser.optionalText(arguments, "status");
        OffsetDateTime validFrom = WriteArgumentParser.optionalTimestamp(arguments, "valid_from");
        return writeToolService.addDrawer(
                principal,
                content,
                wing,
                hall,
                room,
                source,
                tags,
                importance,
                summary,
                keyPoints,
                insight,
                actionability,
                status,
                validFrom
        );
    }
}
