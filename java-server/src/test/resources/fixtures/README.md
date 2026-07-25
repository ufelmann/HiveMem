# Captured Vistierie response envelopes

These two files are **verbatim response bodies from prod Vistierie**, captured on 2026-07-25.
They are not hand-written, and they must not be edited to match what code expects.

## Why they are captured rather than written

The cost-accounting parser reads field names from this envelope. A hand-written fixture
encodes whatever names the author assumed and then passes, while production silently books
zeros — the tolerant parser never raises. Both defects this fixture pins were invisible in the
spec text and only showed up in a real body:

- the `usage` fields are **camelCase** (`cacheCreationInputTokens`, not Anthropic's raw
  `cache_creation_input_tokens`), while `cost_micros` alongside them is snake_case;
- `model` is the model Vistierie **actually routed to** (`claude-sonnet-5`), not the one the
  caller asked for (the complete-call requested `claude-haiku-4-5`).

## How they were captured

From the prod host, against the published port, with the deployed tenant token:

```bash
curl -sS -X POST http://127.0.0.1:8090/llm/complete \
  -H "Authorization: Bearer $HIVEMEM_VISTIERIE_TOKEN" -H "content-type: application/json" \
  -d '{"agent_name":"document-separator","purpose":"summarize_cell","realm":"documents",
       "model":"claude-haiku-4-5","system":"Reply with the single word OK.",
       "messages":[{"role":"user","content":"ping"}],"max_tokens":16}'
```

The vision body is the same call shape against `/llm/vision` with a 1×1 PNG.

## Note on the token counts

`cacheCreationInputTokens: 23097` for a two-word prompt is not document content — it is the
claude-bridge (Claude Code CLI) system prompt, which is charged as cached input on every
subscription-routed call. Useful to know before reading these numbers as HiveMem payload size.

`cost_micros` is **EUR**-micros (Vistierie's `PriceTable` converts at a fixed 1 USD = 0.92 EUR);
it reads 0 here because both calls were routed to the Claude subscription, which has no
marginal cost.
