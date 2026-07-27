package com.hivemem.tools.admin;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.contradiction.ContradictionService;
import com.hivemem.contradiction.PredicateCardinalityRepository;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import com.hivemem.write.WriteArgumentParser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Lists predicate cardinality verdicts, or (with {@code set}) records a human override ({@link
 * ContradictionService#setCardinality}).
 *
 * <p>Registered in {@code ADMIN_TOOLS} for the same reason as {@link
 * ResolveContradictionToolHandler}: setting {@code multi_valued} supersedes every open
 * contradiction pair for the predicate, and a human override outranks every future judge verdict
 * permanently ({@link PredicateCardinalityRepository#setByHuman}) — this is a decision only a
 * human, not an AGENT-role write token, should be able to make.
 */
@Component
@Order(55)
@ConditionalOnProperty(name = "hivemem.contradiction.enabled", havingValue = "true")
public class PredicateCardinalityToolHandler implements ToolHandler {

    private static final List<String> VALID_SET_VALUES = List.of("single_valued", "multi_valued");

    private final ContradictionService service;

    public PredicateCardinalityToolHandler(ContradictionService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return "predicate_cardinality";
    }

    @Override
    public String description() {
        return "List predicate cardinality verdicts, or (with 'set') a human override that "
                + "outranks every judge verdict and, for multi_valued, supersedes the predicate's open "
                + "contradiction pairs.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .optionalString("predicate", "Filter by exact predicate (list mode), or the predicate to set")
                .optionalEnumString("set", "Set this predicate's cardinality (requires predicate and reason)",
                        VALID_SET_VALUES.toArray(String[]::new))
                .optionalString("reason", "Required when 'set' is given; human-readable rationale")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String predicate = WriteArgumentParser.optionalText(arguments, "predicate");
        String set = optionalSet(arguments);
        if (set == null) {
            return service.listCardinality(predicate);
        }
        if (predicate == null) {
            throw new IllegalArgumentException("predicate is required when 'set' is given");
        }
        String reason = requiredReason(arguments);
        return service.setCardinality(predicate, set, reason);
    }

    private static String requiredReason(JsonNode arguments) {
        String reason = WriteArgumentParser.optionalText(arguments, "reason");
        if (reason == null) {
            throw new IllegalArgumentException("reason is required when 'set' is given");
        }
        return reason;
    }

    private static String optionalSet(JsonNode arguments) {
        String set = WriteArgumentParser.optionalText(arguments, "set");
        if (set == null) {
            return null;
        }
        if (!VALID_SET_VALUES.contains(set)) {
            throw new IllegalArgumentException(
                    "Invalid set '" + set + "'. Must be one of: " + String.join(", ", VALID_SET_VALUES));
        }
        return set;
    }
}
