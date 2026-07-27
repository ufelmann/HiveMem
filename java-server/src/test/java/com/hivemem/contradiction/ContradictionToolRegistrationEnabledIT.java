package com.hivemem.contradiction;

import com.hivemem.mcp.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The enabled counterpart of {@link ContradictionToolRegistrationIT}: with
 * {@code hivemem.contradiction.enabled=true} (and {@code hivemem.queen.enabled=true}, required by
 * {@link ContradictionStartupGate}), all three contradiction MCP tools must be registered and
 * resolvable by {@link ToolRegistry}.
 */
class ContradictionToolRegistrationEnabledIT extends ContradictionITSupport {

    @Autowired ToolRegistry registry;

    @DynamicPropertySource
    static void enableContradiction(DynamicPropertyRegistry r) {
        r.add("hivemem.contradiction.enabled", () -> "true");
        r.add("hivemem.queen.enabled", () -> "true");
    }

    @Test
    void allThreeContradictionToolsAreRegisteredWhenTheFeatureIsEnabled() {
        assertThat(registry.resolve("contradictions")).isPresent();
        assertThat(registry.resolve("resolve_contradiction")).isPresent();
        assertThat(registry.resolve("predicate_cardinality")).isPresent();
    }
}
