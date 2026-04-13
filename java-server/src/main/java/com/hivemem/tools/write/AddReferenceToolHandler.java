package com.hivemem.tools.write;

import com.fasterxml.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.write.WriteArgumentParser;
import com.hivemem.write.WriteToolService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(25)
public class AddReferenceToolHandler implements ToolHandler {

    private final WriteToolService writeToolService;

    public AddReferenceToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
    }

    @Override
    public String name() {
        return "hivemem_add_reference";
    }

    @Override
    public String description() {
        return "Add a source reference.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String title = WriteArgumentParser.requiredText(arguments, "title");
        String url = WriteArgumentParser.optionalText(arguments, "url");
        String author = WriteArgumentParser.optionalText(arguments, "author");
        String refType = WriteArgumentParser.optionalText(arguments, "ref_type");
        String status = WriteArgumentParser.optionalText(arguments, "status");
        String notes = WriteArgumentParser.optionalText(arguments, "notes");
        java.util.List<String> tags = WriteArgumentParser.optionalTextList(arguments, "tags");
        Integer importance = WriteArgumentParser.optionalInteger(arguments, "importance");
        return writeToolService.addReference(title, url, author, refType, status, notes, tags, importance);
    }
}
