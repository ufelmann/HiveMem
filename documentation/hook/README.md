# Hook Integration

The hook automatically injects relevant memory cells into Claude Code sessions before each prompt is processed. Claude sees past decisions, discoveries, and context without having to be explicitly asked.

The hook is an **enhancement, not a replacement** for explicit `hivemem_search` / `hivemem_get_cell` tool calls. CLAUDE.md guidance still applies; the hook removes the burden of remembering to search.

## How It Works

```
User types prompt
       ↓
Claude Code → POST /hooks/context (prompt + session_id + cwd)
       ↓
HiveMem runs filtering pipeline
       ↓
Returns <hivemem_context> block (or empty string)
       ↓
Claude sees prompt + injected context
```

See [pipeline.md](pipeline.md) for details on the 6 filtering stages.

## Setup

1. Create a dedicated reader-role token:

   ```bash
   docker exec hivemem hivemem-token create claude-code-hook --role reader
   # Copy the printed token value once — it is not shown again.
   ```

2. Export it where Claude Code can read it:

   ```bash
   export HIVEMEM_HOOK_TOKEN=<token>
   ```

3. Merge into your `~/.claude/settings.json` (or your project's `.claude/settings.json`):

   ```json
   {
     "hooks": {
       "UserPromptSubmit": [
         {
           "hooks": [
             {
               "type": "http",
               "url": "http://localhost:8421/hooks/context",
               "timeout": 5,
               "headers": {
                 "Authorization": "Bearer $HIVEMEM_HOOK_TOKEN"
               },
               "allowedEnvVars": ["HIVEMEM_HOOK_TOKEN"]
             }
           ]
         }
       ]
     }
   }
   ```

4. Restart Claude Code. The hook is now active.

## Behaviour

- Trivial prompts (fewer than 5 words, meta-phrases like `"ok"`/`"weiter"`/`"thanks"`, pure code blocks, prompts prefixed with `!nomem`) skip the search entirely.
- Use `!mem <prompt>` to force injection on a prompt that would otherwise be skipped.
- Within a single session, the same cell is suppressed for 5 turns after injection to prevent context bloat.
- Injected blocks contain only L1 summaries and cell IDs — never full content. The agent fetches details on demand.
- Internal failures collapse to an empty `additionalContext`. The hook never blocks the user's message.

## Disabling

Remove the hook block from your `~/.claude/settings.json`, or set `hivemem.hooks.enabled: false` on the server.

## Trade-offs

Each hook call adds ~50-200ms before the LLM call and ~50-200 tokens to the context window, in exchange for context continuity without explicit retrieval. If your workflow rarely benefits from prior knowledge, leave it off.

## Further Reading

- [Pipeline stages](pipeline.md) — how the 6-stage filter works
- [Configuration reference](configuration.md) — all `hivemem.hooks.*` properties
- [Output format](output-format.md) — the `<hivemem_context>` block structure
- [Roadmap](roadmap.md) — known limitations and planned improvements
