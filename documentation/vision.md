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
`hivemem.attachment.vision-daily-budget-usd`.

## References

- Cowan, N. (2001). *The magical number 4 in short-term memory.* Behavioral and Brain Sciences, 24(1), 87-114.
- Sweller, J. (1988). *Cognitive Load During Problem Solving.* Cognitive Science, 12(2), 257-285.
- Clark, A. & Chalmers, D. (1998). *The Extended Mind.* Analysis, 58(1), 7-19.
- Ebbinghaus, H. (1885). *Uber das Gedachtnis.*
- Ahrens, S. (2017). *How to Take Smart Notes.* CreateSpace.
- Forte, T. (2022). *Building a Second Brain.* Atria Books.
