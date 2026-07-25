# Vision & Research

HiveMem is built on the premise that well-structured external knowledge systems are not just storage -- they extend cognition. Every design decision is grounded in research on how humans process, retain, and retrieve information.

## Scientific Foundations

| Theory | Key Insight | HiveMem Consequence |
|---|---|---|
| **Working Memory Limitation** (Cowan, 2001) | Humans hold ~4 items in working memory | Wake-up context delivers max 15-20 items, prioritized by importance |
| **Cognitive Load Theory** (Sweller, 1988) | Disorganized information wastes mental resources needed for thinking | Realms/Signals/Topics taxonomy, Blueprints, progressive summarization |
| **Extended Mind Thesis** (Clark & Chalmers, 1998) | Well-used external tools become genuine extensions of cognition | Proactive capturing, graph traversal for hidden connections, synthesis agents |
| **Forgetting Curve** (Ebbinghaus, 1885) | 90% of learned information is lost within a week | Immediate capture at session end, proactive storage of decisions |

## PKM Frameworks

**Zettelkasten** (Luhmann) -- Atomic notes + linking. Knowledge emerges from connections, not hierarchies. Luhmann produced 70 books and 400 papers from 90,000 linked notes.

*What HiveMem adopts:* Atomic cells (one topic per cell), knowledge graph as linking (facts), cell-to-cell tunnels with temporal versioning (related_to, builds_on, contradicts, refines).
*What HiveMem does differently:* Semi-automatic linking -- LLM agents create tunnels after archiving based on semantic search. Bidirectional traversal. Temporal validity -- notes and tunnels can expire.

**PARA** (Tiago Forte) -- Projects / Areas / Resources / Archive. Sorted by actionability, not topic.

*What HiveMem adopts:* Actionability field (actionable / reference / someday / archive). Wake-up prioritizes actionable over reference. Realms map to Areas.

## Image sub-types (since 2026-05-02)

Each image-format attachment (`image/jpeg`, `image/png`, `image/gif`,
`image/webp`) is classified by Claude Haiku into one of three sub-types in the
same Vision call that produces the cell content:

| Sub-type | Cell content | Tag |
|----------|--------------|-----|
| `whiteboard_photo` | Extracted text + structural notes (hierarchy, arrows) | `subtype_whiteboard_photo`, `whiteboard`, `has_text` |
| `document_scan` | Verbatim transcription in reading order, tables as Markdown | `subtype_document_scan`, `document`, `has_text` |
| `photo_general` | Concise description (max 200 words) | `subtype_photo_general`, `photo` |

Tag values are driven by `extraction-profiles/image-*.yaml`. To change which
extra tags get applied per sub-type, edit the YAML — no code change needed.

**Cost:** the sub-type classification is part of the same Vision call as the
content generation (no extra request). `max_tokens` for image describe is 4000
(was 600 before this change), to fit verbatim transcription of full document
pages. Daily budget is shared with the OCR Vision fallback via
`hivemem.attachment.vision-daily-budget-usd` — an **EUR** budget despite the key name, see
[Cost logging](#cost-logging).

### Cost logging

Every vision call emits one INFO line, mirroring the summarize path (see
[summarizer.md](summarizer.md#logging--cost-visibility)):

    Vision LLM call cell=<uuid> provider=<provider> model=<model> in=<uncached> cacheW=<tokens> cacheR=<tokens> out=<tokens> cost=€<cost> day=€<spend>/<budget>

The OCR vision fallback logs the same line with an extra `page=<n>`. `provider` and
`model` are the ones Vistierie actually routed to — not what HiveMem requested, so they
may differ from the configured model; `cost` is the amount that was booked to
`vision_usage`.

**Amounts are EUR.** They are Vistierie's `cost_micros` for the call it actually routed,
booked unchanged (EUR-micros → EUR); only when the response carries no cost does HiveMem
fall back to an internal price table, logging a WARN when it does. The `usd` in
`vision-daily-budget-usd` and in `vision_usage.total_cost_usd` is a historical name kept
for config and schema compatibility — the budget it configures is an EUR budget.

**Limitation — the daily gate does not bound subscription traffic.** A call Vistierie
routes over the Claude subscription (`provider=claude-subscription`) reports zero cost and
is booked as `0.00` by design, because that is what it costs. Such calls never move
`total_cost_usd`, so the daily budget never trips for them, however many run. The cap
bounds pay-per-token routes only.

**Figures recorded before this version are not comparable.** Earlier rows were estimated
from the model HiveMem *requested* rather than the one Vistierie routed to, omitted cache
read/write tokens, and were labelled USD. Old `vision_usage` rows are kept as they are (the
gate reads only the current UTC day), so a trend across the upgrade date compares two
different measurements.

A call the provider answers with blank text is still a billed call: its cost is
booked before the failure is handled, so the hourly `vision_pending` retry cannot
spend without ever moving `total_cost_usd`. A failure while booking the cost is
logged at WARN and never discards the description or transcript.

**Agent identity:** every `/llm/vision` request is billed to a Vistierie agent named by
`hivemem.attachment.vision-agent-name` (default `document-separator`). Vistierie requires
this field and rejects a request without it with HTTP 400 *before* any provider call, so a
missing or blank value disables image description and the OCR Vision fallback entirely — the
symptom is cells stuck on `vision_pending` or tagged `ocr_failed`, with no matching rows in
Vistierie's call audit.

## References

- Cowan, N. (2001). *The magical number 4 in short-term memory.* Behavioral and Brain Sciences, 24(1), 87-114.
- Sweller, J. (1988). *Cognitive Load During Problem Solving.* Cognitive Science, 12(2), 257-285.
- Clark, A. & Chalmers, D. (1998). *The Extended Mind.* Analysis, 58(1), 7-19.
- Ebbinghaus, H. (1885). *Uber das Gedachtnis.*
- Ahrens, S. (2017). *How to Take Smart Notes.* CreateSpace.
- Forte, T. (2022). *Building a Second Brain.* Atria Books.
