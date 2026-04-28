# Hook Output Format

The hook returns an `additionalContext` block injected transparently into the Claude Code conversation:

```xml
<hivemem_context turn="3">
Relevant (summaries only — use hivemem_get_cell for details):
- Phase 3 plan: SDK wrapper, 4 weeks (id: 11111111-1111-1111-1111-111111111111)
- bge-m3 setup on prod server (id: 22222222-2222-2222-2222-222222222222)
</hivemem_context>
```

**Summaries only** — full cell content is available via `hivemem_get_cell` using the IDs in the injected block. The agent fetches details on demand.

The `turn` attribute reflects the current session turn count, used by the dedup window.

If no cells pass the pipeline (all filtered out), `additionalContext` is returned as an empty string and nothing is injected.

Internal failures (DB down, search error) also collapse to an empty `additionalContext`. The hook never blocks the user's message.
