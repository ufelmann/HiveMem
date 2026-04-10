---
name: hivemem-archive
description: Archive the current session into HiveMem — extract knowledge, facts, and decisions
trigger: User says "archive", "save session", or "save to hivemem"
---

# Archive This Session

Analyze the current conversation and persist the knowledge into HiveMem.

## Steps

1. **Summarize** the session in 2-3 sentences. What was discussed? What was decided?

2. **Classify** the content:
   - **Wing**: Which domain? (e.g. engineering, product, personal, infrastructure, general)
   - **Room**: What specific topic? (e.g. api-migration, auth-refactor, deployment)
   - **Hall**: What type of knowledge?
     - facts: decisions, directions, agreed approaches
     - events: sessions, milestones, debugging sessions
     - discoveries: breakthroughs, new insights
     - preferences: user preferences, habits
     - advice: recommendations, solution patterns

3. **Store the drawer** — call `add_drawer` with:
   - content: the full summary (not abbreviated, not compressed)
   - wing, room, hall as determined above
   - source: the current client (claude-code, claude-desktop, etc.)
   - tags: relevant keywords

4. **Extract KG facts** — for each decision, relationship, or status change:
   - call `kg_add` with subject → predicate → object
   - Examples:
     - ("platform_team", "decided_to_use", "GraphQL")
     - ("Alice", "discussed_with", "Bob")
     - ("api_gateway", "status", "in_development")

5. **Check for contradictions** — if a new fact contradicts an existing one:
   - call `search_kg` to find the old fact
   - call `kg_invalidate` on the old fact
   - call `kg_add` for the new fact
   - mention the update in the drawer content

6. **Confirm** — tell the user what was archived:
   - Wing/Room/Hall
   - Number of KG facts added
   - Any contradictions resolved
