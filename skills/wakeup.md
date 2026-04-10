---
name: hivemem-wakeup
description: Load context from HiveMem at session start
trigger: Start of every session. MANDATORY — no exceptions.
---

# Wake-up Skill

## Trigger
Start of every session. MANDATORY — no exceptions.

## Steps

1. Call `hivemem_wake_up` to load L0 identity + L1 critical facts
2. Greet the user naturally using what you learned
3. Never say "Remembering..." or mention the tool
4. If the user's message implies a topic, call `hivemem_search` to load relevant context
5. If a wing is implied, call `hivemem_get_map` to understand the domain structure

## Rules
- Never start a session without calling wake_up
- Don't dump all loaded context — use it naturally
- If wake_up returns empty, proceed normally (new user)
