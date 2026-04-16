package com.hivemem.tools.write;

import com.fasterxml.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.write.WriteArgumentParser;
import com.hivemem.write.WriteToolService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(21)
public class CheckContradictionToolHandler implements ToolHandler {

    private final WriteToolService writeToolService;

    public CheckContradictionToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
    }

    @Override
    public String name() {
        return "hivemem_check_contradiction";
    }

    @Override
    public String description() {
        return "Check for active facts with the same subject and predicate but a different object.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String subject = WriteArgumentParser.requiredText(arguments, "subject");
        String predicate = WriteArgumentParser.requiredText(arguments, "predicate");
        String newObject = WriteArgumentParser.requiredText(arguments, "new_object");
        return writeToolService.checkContradiction(subject, predicate, newObject);
    }
}
