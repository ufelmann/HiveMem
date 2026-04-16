package com.hivemem.tools.write;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.write.WriteArgumentParser;
import com.hivemem.write.WriteToolService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(32)
public class CheckDuplicateToolHandler implements ToolHandler {

    private final WriteToolService writeToolService;

    public CheckDuplicateToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
    }

    @Override
    public String name() {
        return "hivemem_check_duplicate";
    }

    @Override
    public String description() {
        return "Find existing drawers with high semantic similarity.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String content = WriteArgumentParser.requiredText(arguments, "content");
        double threshold = optionalThreshold(arguments);
        return writeToolService.checkDuplicate(content, threshold);
    }

    private static double optionalThreshold(JsonNode arguments) {
        if (arguments == null || !arguments.has("threshold") || arguments.get("threshold").isNull()) {
            return 0.95d;
        }
        JsonNode node = arguments.get("threshold");
        if (!node.isNumber()) {
            throw new IllegalArgumentException("Invalid threshold");
        }
        double value = node.asDouble();
        if (!Double.isFinite(value) || value < -1.0d || value > 1.0d) {
            throw new IllegalArgumentException("Invalid threshold");
        }
        return value;
    }
}
