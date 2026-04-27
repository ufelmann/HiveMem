# HiveMem Hook — Context Auto-Injection

The hook automatically injects relevant memory cells into Claude Code sessions before each prompt is processed. Claude sees past decisions, discoveries, and context without having to be explicitly asked.

## How It Works

Claude Code fires a `UserPromptSubmit` HTTP hook before sending each prompt. The hook endpoint at `/hooks/context` searches HiveMem for relevant cells and returns them as `additionalContext` — injected into the conversation transparently.

```
User types prompt
       ↓
Claude Code → POST /hooks/context (prompt + session_id + cwd)
       ↓
HiveMem runs filtering pipeline (see below)
       ↓
Returns <hivemem_context> block (or empty string)
       ↓
Claude sees prompt + injected context
```

### Client Configuration (`~/.claude/settings.json`)

```json
{
  "hooks": {
    "UserPromptSubmit": [{
      "hooks": [{
        "type": "http",
        "url": "http://192.168.178.145:8421/hooks/context?threshold=0.65&maxCells=3",
        "timeout": 5,
        "headers": { "Authorization": "Bearer $HIVEMEM_HOOK_TOKEN" },
        "allowedEnvVars": ["HIVEMEM_HOOK_TOKEN"]
      }]
    }]
  }
}
```

`threshold` and `maxCells` can be overridden per-request via query params. Server defaults apply if omitted.

---

## Filtering Pipeline

Each incoming prompt passes through these stages in order. Any stage can short-circuit to an empty response.

### Stage 1 — Skip Heuristics

Prompts that carry no useful search signal are rejected immediately — no embedding, no DB call.

Skipped if:
- Prompt is null or blank
- Prompt starts with `!nomem` (explicit opt-out)
- Prompt is in the meta-phrase list (`ok`, `ja`, `danke`, `wie geht's`, `alles klar`, …)
- Prompt is a pure code block (starts and ends with ` ``` `)
- Fewer than 5 words
- Starts with a social phrase (`wie geht `, `wie läuft `, `hallo`, `guten morgen`, …) and has ≤ 8 words

Force-include: prefix prompt with `!mem ` to bypass all skip logic.

### Stage 2 — Embedding + Search

The prompt is embedded via the configured embedding model (`paraphrase-multilingual-MiniLM-L12-v2`) and passed to `ranked_search` — a PostgreSQL function combining five signals:

| Signal | Default weight | What it measures |
|---|---|---|
| `score_semantic` | 0.30 | Cosine similarity between prompt and cell embeddings |
| `score_keyword` | 0.15 | Full-text match (tsvector) |
| `score_recency` | 0.15 | How recently the cell was created |
| `score_importance` | 0.15 | Manually assigned importance (1–5) |
| `score_popularity` | 0.15 | Access frequency |
| `score_graph_proximity` | 0.10 | Distance via tunnel graph |

`ranked_search` applies a hard pre-filter: `score_semantic > 0.3 OR score_keyword > 0`. Results are ordered by `score_total` descending.

### Stage 3 — Relevance Threshold

```
score_total >= relevanceThreshold  (default: 0.65)
```

Cells below this threshold are discarded. Tunable via `hivemem.hooks.relevance-threshold` or the `?threshold=` query param.

### Stage 4 — Semantic Floor

```
score_semantic >= minSemanticScore  (default: 0.35)
```

Prevents pure keyword matches from being injected. A cell that matches on keyword overlap alone (e.g. "deployment" appearing in both prompt and cell) without semantic similarity is filtered out here.

This addresses the failure mode where a topically unrelated cell scores high due to shared vocabulary.

### Stage 5 — Session Dedup

Cells already injected within the last `dedupWindowTurns` turns (default: 5) in the same session are suppressed. Avoids repeating context Claude has already seen.

### Stage 6 — CWD Promotion

If the request includes a `cwd` (current working directory), the last path component is extracted (e.g. `/root/hivemem` → `hivemem`). Cells whose `realm`, `topic`, or `tags` contain this string are sorted to the front, even if their `score_total` is lower than other results.

This is a soft promotion — it reorders results but does not filter. The hard realm filter is planned (see below).

---

## Output Format

```xml
<hivemem_context turn="3">
Relevant (summaries only — use hivemem_get_cell for details):
- Phase 3 plan: SDK wrapper, 4 weeks (id: 11111111-1111-1111-1111-111111111111)
- bge-m3 setup on prod server (id: 22222222-2222-2222-2222-222222222222)
</hivemem_context>
```

Summaries only — full cell content is available via `hivemem_get_cell` if needed.

---

## Configuration Reference

All properties under `hivemem.hooks.*` in `application.yml`:

| Property | Default | Description |
|---|---|---|
| `enabled` | `true` | Master switch |
| `relevance-threshold` | `0.65` | Minimum `score_total` to inject |
| `min-semantic-score` | `0.35` | Minimum `score_semantic` to inject |
| `max-cells` | `3` | Maximum cells injected per turn |
| `dedup-window-turns` | `5` | Turns before a cell can be re-injected |

---

## Known Limitations and Planned Improvements

### Search weights are tuned for ranking, not gating

The default weights (`semantic: 0.30`) are designed for ranked search in the UI. For auto-inject, precision matters more than recall. Planned: a separate `hivemem.hooks.weights.*` block with `semantic: 0.70` so the hook uses a precision preset without affecting UI search.

### Realm filter is soft, not hard

CWD promotion reorders results but does not exclude cells from the wrong realm. A cell tagged `tooling` and a cell tagged `personal` are both candidates when working in `/root/hivemem`. Planned: a configurable `cwd-realm-map` that passes a hard realm filter to `ranked_search`:

```yaml
hivemem.hooks.cwd-realm-map:
  /root/hivemem: tooling
  /home/vu/bogis-skills: work
```

### Skip heuristic is word-count based, not intent based

Current skip triggers on `< 5 words` and social starters. A more robust version would check: does the prompt contain a technical noun (word length > 6)? If not and the first word is a modal verb, skip. Planned improvement to `SkipHeuristics`.

### No feedback loop

If Claude ignores injected context, the system learns nothing. Popularity scores grow only when `hivemem_get_cell` is explicitly called. A PostResponse hook does not exist in Claude Code yet. Longer-term: stronger `weight_popularity` + decay agent as a proxy for relevance feedback.

---

## Testing

The hook mechanics are covered by unit tests in `src/test/java/com/hivemem/hooks/`:

- `SkipHeuristicsTest` — skip/keep decisions for all heuristics
- `HookContextServiceTest` — threshold filter, semantic floor, dedup, CWD promotion
- `HooksControllerTest` — HTTP layer, auth, response format
- `HookBenchmarkTest` — golden-set benchmark with positive and negative cases *(planned)*
