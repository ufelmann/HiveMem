---
name: hivemem-wakeup
description: Load context from HiveMem at session start
trigger: Session start, or user says "wake up", "load context", "what do you know about me"
---

# Wake Up — Load HiveMem Context

Load identity and relevant context from HiveMem at the start of a session.

## Steps

1. **Load identity** — call `wake_up`
   - Returns L0 (identity, ~50 tokens) and L1 (critical facts, ~120 tokens)
   - This is your baseline context about the user

2. **Detect topic** — if the user's first message mentions a specific topic:
   - call `search` with the topic as query (limit 5)
   - call `search_kg` with relevant entities
   - This gives you prior decisions and context

3. **Summarize briefly** — tell the user:
   - "HiveMem loaded. I have [X relevant facts]."
   - Only mention facts directly relevant to the current topic
   - Do NOT dump the entire identity — just confirm you have it

4. **Stay lean** — do NOT load everything. Only load what's relevant.
   Progressive loading: start with L0+L1, load more on demand.
