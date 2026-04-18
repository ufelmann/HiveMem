package com.hivemem.tools.write;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.write.WriteArgumentParser;
import com.hivemem.write.WriteToolService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(19)
public class KgAddToolHandler implements ToolHandler {

    private static final double DEFAULT_CONFIDENCE = 1.0d;

    private final WriteToolService writeToolService;

    public KgAddToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
    }

    @Override
    public String name() {
        return "hivemem_kg_add";
    }

    @Override
    public String description() {
        return "Add a fact triple to the knowledge graph.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String subject = WriteArgumentParser.requiredText(arguments, "subject");
        String predicate = WriteArgumentParser.requiredText(arguments, "predicate");
        String object = WriteArgumentParser.requiredText(arguments, "object_");
        double confidence = WriteArgumentParser.requiredConfidence(arguments, "confidence", DEFAULT_CONFIDENCE);
        java.util.UUID sourceId = WriteArgumentParser.optionalUuid(arguments, "source_id");
        String status = WriteArgumentParser.optionalText(arguments, "status");
        java.time.OffsetDateTime validFrom = WriteArgumentParser.optionalTimestamp(arguments, "valid_from");
        String onConflict = WriteArgumentParser.optionalText(arguments, "on_conflict");
        return writeToolService.kgAdd(principal, subject, predicate, object, confidence, sourceId, status, validFrom, onConflict);
    }
}
