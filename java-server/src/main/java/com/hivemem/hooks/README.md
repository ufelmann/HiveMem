# Hook — Context Auto-Injection

This package implements the `POST /hooks/context` endpoint.

**Operator documentation** (setup, pipeline, configuration, output format, roadmap):
→ [`documentation/hook/`](../../../../../../../../../documentation/hook/)

## Package Contents

- `HooksController` — HTTP endpoint, auth, response formatting
- `HookContextService` — orchestrates the filtering pipeline (threshold, semantic floor, dedup, CWD promotion)
- `SkipHeuristics` — Stage 1: rejects prompts with no useful search signal
- `SessionInjectionCache` — tracks injected cells per session for dedup
- `ContextFormatter` — formats the `<hivemem_context>` output block
- `HookProperties` — `hivemem.hooks.*` configuration binding
