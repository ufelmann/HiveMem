package com.hivemem.tools.admin;

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
import java.util.UUID;

/**
 * A human resolves one pending/deferred contradiction pair ({@link ContradictionService#resolve}).
 *
 * <p>Deliberately registered in {@code ADMIN_TOOLS}, not {@code WRITE_TOOLS}: {@code resolve}
 * invalidates the losing fact through {@code WriteToolService.kgInvalidate(UUID)}, which takes no
 * principal — this handler's permission classification is the only place in the codebase that can
 * enforce "a human chose the winner." {@code AGENT_TOOLS == WRITER_TOOLS}, so registering this as
 * an ordinary write tool would let every AGENT-role token invalidate a committed fact — the same
 * role whose own writes are forced to {@code pending} precisely so a human approves them first.
 */
@Component
@Order(54)
@ConditionalOnProperty(name = "hivemem.contradiction.enabled", havingValue = "true")
public class ResolveContradictionToolHandler implements ToolHandler {

    private static final List<String> VALID_KEEP_VALUES = List.of("fact_a", "fact_b", "both", "requeue");

    private final ContradictionService service;

    public ResolveContradictionToolHandler(ContradictionService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return "resolve_contradiction";
    }

    @Override
    public String description() {
        return "Resolve a pending or deferred contradiction pair: keep one fact and invalidate the "
                + "other, dismiss both as legitimate, or requeue for re-judging.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredUuid("id", "Contradiction pair id")
                .requiredEnumString("keep", "Which fact to keep", VALID_KEEP_VALUES.toArray(String[]::new))
                .requiredString("reason", "Human-readable reason for this decision (audit only, not persisted)")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        UUID id = WriteArgumentParser.requiredUuid(arguments, "id");
        String keep = requiredKeep(arguments);
        String reason = WriteArgumentParser.requiredText(arguments, "reason");
        return service.resolve(id, keep, reason);
    }

    private static String requiredKeep(JsonNode arguments) {
        String keep = WriteArgumentParser.requiredText(arguments, "keep");
        if (!VALID_KEEP_VALUES.contains(keep)) {
            throw new IllegalArgumentException(
                    "Invalid keep '" + keep + "'. Must be one of: " + String.join(", ", VALID_KEEP_VALUES));
        }
        return keep;
    }
}
