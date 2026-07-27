package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.contradiction.ContradictionService;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import com.hivemem.write.WriteArgumentParser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Lists contradiction pairs from {@code fact_contradictions}, defaulting to the pending human
 * review queue ({@link ContradictionService#list}). Read-only: registered in {@code READ_TOOLS}
 * and, additionally, in the scoped-token {@code READ_GLOBAL_TOOLS} bucket alongside {@code
 * search_kg} — a contradiction pair carries no realm of its own to filter a scoped token on.
 */
@Component
@Order(53)
@ConditionalOnProperty(name = "hivemem.contradiction.enabled", havingValue = "true")
public class ContradictionsToolHandler implements ToolHandler {

    private static final List<String> VALID_STATUSES = List.of(
            "in_flight", "retryable", "pending", "resolved",
            "dismissed", "superseded", "not_contradictory", "deferred");

    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 500;

    private final ContradictionService service;

    public ContradictionsToolHandler(ContradictionService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return "contradictions";
    }

    @Override
    public String description() {
        return "List contradiction pairs by status and/or subject, defaulting to the pending review queue.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .optionalEnumString("status", "Filter by status (defaults to 'pending')",
                        VALID_STATUSES.toArray(String[]::new))
                .optionalString("subject", "Filter by exact subject")
                .optionalIntegerInRange("limit", "Maximum number of results (default 50)", MIN_LIMIT, MAX_LIMIT)
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String status = optionalStatus(arguments);
        String subject = WriteArgumentParser.optionalText(arguments, "subject");
        Integer limit = WriteArgumentParser.optionalInteger(arguments, "limit");
        return service.list(status, subject, limit);
    }

    private static String optionalStatus(JsonNode arguments) {
        String status = WriteArgumentParser.optionalText(arguments, "status");
        if (status == null) {
            return null;
        }
        if (!VALID_STATUSES.contains(status)) {
            throw new IllegalArgumentException(
                    "Invalid status '" + status + "'. Must be one of: " + String.join(", ", VALID_STATUSES));
        }
        return status;
    }
}
