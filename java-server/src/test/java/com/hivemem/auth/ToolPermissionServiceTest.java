package com.hivemem.auth;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolPermissionServiceTest {

    private final ToolPermissionService svc = new ToolPermissionService();

    @Test
    void queenRunsToolsAreAdminOnly() {
        assertThat(svc.isAllowed(AuthRole.ADMIN, "queen_runs")).isTrue();
        assertThat(svc.isAllowed(AuthRole.ADMIN, "queen_run_detail")).isTrue();
        assertThat(svc.isAllowed(AuthRole.WRITER, "queen_runs")).isFalse();
        assertThat(svc.isAllowed(AuthRole.READER, "queen_run_detail")).isFalse();
        assertThat(svc.isAllowed(AuthRole.AGENT, "queen_runs")).isFalse();
        assertThat(svc.isAllowed(AuthRole.AGENT, "queen_run_detail")).isFalse();
    }

    @Test
    void contradictionsIsReadableByReaderAndAdmin() {
        assertThat(svc.isAllowed(AuthRole.READER, "contradictions")).isTrue();
        assertThat(svc.isAllowed(AuthRole.ADMIN, "contradictions")).isTrue();
        assertThat(svc.isAllowed(AuthRole.WRITER, "contradictions")).isTrue();
        assertThat(svc.isAllowed(AuthRole.AGENT, "contradictions")).isTrue();
    }

    /**
     * resolve_contradiction and predicate_cardinality must be ADMIN-only, deliberately NOT
     * reachable by WRITER or AGENT: both ultimately invalidate a committed fact through {@code
     * WriteToolService.kgInvalidate}, which takes no principal, so this permission check is the
     * only place that can enforce "a human chose the winner" — an ordinary AGENT-role write token
     * (AGENT_TOOLS == WRITER_TOOLS) must not be able to invalidate a fact its own writes are
     * forced through pending review to avoid.
     */
    @Test
    void resolveContradictionAndPredicateCardinalityAreAdminOnly() {
        for (String tool : List.of("resolve_contradiction", "predicate_cardinality")) {
            assertThat(svc.isAllowed(AuthRole.READER, tool)).as(tool).isFalse();
            assertThat(svc.isAllowed(AuthRole.WRITER, tool)).as(tool).isFalse();
            assertThat(svc.isAllowed(AuthRole.AGENT, tool)).as(tool).isFalse();
            assertThat(svc.isAllowed(AuthRole.ADMIN, tool)).as(tool).isTrue();
        }
    }

    /**
     * Pins the total visible tool count for ADMIN so an accidental bucket move (e.g.
     * resolve_contradiction slipping into WRITE_TOOLS instead of ADMIN_TOOLS, which would not
     * change this count since ADMIN sees the union of both) is caught alongside the more targeted
     * assertions above. Count derivation: the README's MCP-tool badge was 48 before this task
     * (documentation/../README.md), and this task adds exactly 3 new tools.
     */
    @Test
    void adminSeesExactlyFiftyOneTools() {
        assertThat(svc.allowedTools(AuthRole.ADMIN)).hasSize(51);
    }
}
