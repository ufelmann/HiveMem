package com.hivemem.auth;

import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Service
public class ToolPermissionService {

    private static Set<String> tools(String... toolNames) {
        return Set.of(toolNames);
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        Set<String> combined = new HashSet<>(first);
        combined.addAll(second);
        return Set.copyOf(combined);
    }

    private static final Set<String> READ_TOOLS = tools(
            "hivemem_status",
            "hivemem_search",
            "hivemem_search_kg",
            "hivemem_get_drawer",
            "hivemem_list_wings",
            "hivemem_list_halls",
            "hivemem_traverse",
            "hivemem_quick_facts",
            "hivemem_time_machine",
            "hivemem_wake_up",
            "hivemem_drawer_history",
            "hivemem_fact_history",
            "hivemem_pending_approvals",
            "hivemem_get_blueprint",
            "hivemem_reading_list",
            "hivemem_list_agents",
            "hivemem_diary_read"
    );

    private static final Set<String> WRITE_TOOLS = tools(
            "hivemem_add_drawer",
            "hivemem_add_tunnel",
            "hivemem_check_duplicate",
            "hivemem_kg_add",
            "hivemem_kg_invalidate",
            "hivemem_update_identity",
            "hivemem_add_reference",
            "hivemem_link_reference",
            "hivemem_remove_tunnel",
            "hivemem_revise_drawer",
            "hivemem_revise_fact",
            "hivemem_check_contradiction",
            "hivemem_register_agent",
            "hivemem_diary_write",
            "hivemem_update_blueprint"
    );

    private static final Set<String> ADMIN_TOOLS = tools(
            "hivemem_approve_pending",
            "hivemem_health",
            "hivemem_log_access",
            "hivemem_refresh_popularity"
    );

    private static final Set<String> WRITER_TOOLS = union(READ_TOOLS, WRITE_TOOLS);
    private static final Set<String> AGENT_TOOLS = WRITER_TOOLS;
    private static final Set<String> ALL_TOOLS = union(WRITER_TOOLS, ADMIN_TOOLS);

    private static final Map<AuthRole, Set<String>> ROLE_TOOLS = Map.of(
            AuthRole.ADMIN, ALL_TOOLS,
            AuthRole.WRITER, WRITER_TOOLS,
            AuthRole.READER, READ_TOOLS,
            AuthRole.AGENT, AGENT_TOOLS
    );

    public boolean isAllowed(AuthRole role, String toolName) {
        return allowedTools(role).contains(toolName);
    }

    public Set<String> allowedTools(AuthRole role) {
        if (role == null) {
            return Set.of();
        }
        return ROLE_TOOLS.getOrDefault(role, Set.of());
    }
}
