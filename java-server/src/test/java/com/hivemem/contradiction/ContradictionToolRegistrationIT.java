package com.hivemem.contradiction;

import com.hivemem.mcp.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the three contradiction MCP tools are absent while the feature is disabled, and that the
 * context still boots — using a non-lazy full application context ({@link ContradictionITSupport},
 * which carries no {@code spring.main.lazy-initialization} property). NOT
 * {@code HiveMemApplicationTest}: that class sets lazy init, under which a missing/broken bean is
 * simply never instantiated, so it would prove nothing about wiring either way.
 *
 * <p>This class deliberately adds no {@code hivemem.contradiction.enabled} property, so it shares
 * the disabled-by-default context every other non-{@code ContradictionServiceIT}-style test in this
 * package already uses (see {@link ContradictionITSupport}'s Javadoc on context-cache reuse). The
 * enabled side is {@link ContradictionToolRegistrationEnabledIT}, a separate top-level class: two
 * different property sets are two different Spring context-cache keys, and Failsafe only discovers
 * top-level {@code *IT} classes, not nested statics.
 */
class ContradictionToolRegistrationIT extends ContradictionITSupport {

    @Autowired ToolRegistry registry;

    @Test
    void noContradictionToolIsRegisteredWhenTheFeatureIsDisabled() {
        assertThat(registry.resolve("contradictions")).isEmpty();
        assertThat(registry.resolve("resolve_contradiction")).isEmpty();
        assertThat(registry.resolve("predicate_cardinality")).isEmpty();
    }
}
