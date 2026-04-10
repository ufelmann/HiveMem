---
name: hivemem-archive
description: Archive the current session into HiveMem — extract knowledge, facts, and decisions
trigger: User says "archive", "save session", "save to hivemem", or session is ending.
---

# Archive Session Skill

## Trigger
User says "archive", "save session", "save to hivemem", or session is ending.

## Steps

1. **Summarize** the session in 2-3 sentences (what was discussed, what was decided)

2. **Classify** the content:
   - `wing`: domain (engineering, product, personal, infrastructure, team)
   - `room`: specific topic in kebab-case (auth-migration, hivemem-v2, team-split)
   - `hall`: knowledge type (facts, events, discoveries, preferences, advice)

3. **Check for duplicates**: Call `hivemem_check_duplicate` with the summary

4. **Store the drawer** with all progressive summarization layers:
   - `content`: full summary (L0)
   - `summary`: one sentence (L1)
   - `key_points`: 3-5 core takeaways (L2)
   - `insight`: what does this mean for the user? (L3)
   - `actionability`: actionable / reference / someday / archive
   - `importance`: 1-5 (1=critical)
   - `source`: which client (claude-code, claude-desktop, etc.)

5. **Extract facts** for each decision, relationship, or status change:
   - Call `hivemem_check_contradiction` first
   - If contradiction: call `hivemem_kg_invalidate` on old fact
   - Then call `hivemem_kg_add` with the new fact
   - Always include `valid_from` date

6. **Update Map of Content** if a wing's structure changed significantly:
   - Call `hivemem_get_map` for the wing
   - If the session added a new room or changed priorities: `hivemem_update_map`

7. **Confirm** to user: wing/room/hall, drawer count, facts added, contradictions resolved
