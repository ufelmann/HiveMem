# Tools

HiveMem exposes **34 MCP tools** across search, knowledge graph, progressive summarization, agent fleet, references, attachments, and admin.

## Feature Overview

- **34 MCP tools** across search, knowledge graph, progressive summarization, agent fleet, references, attachments, and admin
- **6-signal ranked search** — semantic similarity + keyword match + recency + importance + popularity + graph proximity
- **Append-only versioning** — never lose history, revise with parent_id chains, point-in-time queries
- **Progressive summarization** — content, summary, key_points, insight per cell
- **Temporal knowledge graph** — facts with valid_from/valid_until, contradiction detection, multi-hop traversal
- **Role-based token auth** — multiple tokens, 4 roles (admin/writer/reader/agent), per-role tool visibility
- **Agent fleet** with approval workflow — agents write pending suggestions, only admins approve
- **Blueprints** — curated narrative overviews per realm, append-only versioned
- **References & reading list** — track sources, link to cells, filter by type/status
- **Spring Boot 4.0.5 + Java 25** — MCP server with jOOQ, Flyway migrations, Caffeine cache
- **Automatic embedding reencoding** — detects model changes at startup, re-encodes all vectors with backup and progress tracking
- **Comprehensive JUnit + Testcontainers suite** — unit, integration, HTTP end-to-end, performance, security, concurrency

## Tool List

**Read (15):**

1. `status`: System overview and counts.
2. `search`: Semantic similarity + keyword search; returns metadata by default and supports `include` for optional fields.
3. `search_kg`: Knowledge graph triple lookup.
4. `get_cell`: Read a single knowledge item (logs access automatically); supports `include` for optional fields including content.
5. `list`: Navigate the Realm→Signal→Topic→Cell hierarchy (omit all params for realms; add `realm` for signals; add `realm`+`signal` for topics; add `realm`+`signal`+`topic` for cells).
6. `traverse`: Recursive graph traversal.
7. `quick_facts`: Context-aware facts about an entity.
8. `time_machine`: Historical knowledge retrieval.
9. `wake_up`: Initial session context.
10. `history`: Trace revisions of a cell or fact (type-dispatched, recursive CTE depth cap 100).
11. `pending_approvals`: List work awaiting review.
12. `get_blueprint`: Narrative realm overviews.
13. `reading_list`: Manage unread/in-progress sources.
14. `list_agents`: View active agent fleet.
15. `diary_read`: Read agent diary entries.

**Write (17):**

16. `add_cell`: Store a cell with content, summary, key points, and insight; optional `dedupe_threshold` runs an embedding-based dedupe gate in one call.
17. `add_tunnel`: Link two cells together.
18. `kg_add`: Fact triple; optional `on_conflict` (`insert`|`return`|`reject`) gates against active conflicts.
19. `kg_invalidate`: Soft-delete/expire a fact.
20. `update_identity`: Update session context facts.
21. `add_reference`: Store source documents/URLs.
22. `link_reference`: Cite source for a cell.
23. `remove_tunnel`: Expire a cell link.
24. `revise_cell`: Create a new version of a cell.
25. `revise_fact`: Create a new version of a fact.
26. `register_agent`: Add an agent to the fleet.
27. `diary_write`: Agent-private reflection tool.
28. `update_blueprint`: Update realm narrative.
29. `reclassify_cell`: Move a cell to a different realm/signal/topic in-place without creating a new revision. Leaves content, embeddings, tunnels, facts, and references untouched. Use for taxonomy migrations.

**Attachments (3):**

30. `upload_attachment`: Upload a file attachment (Base64-encoded). Required params: `realm` (target realm), `data` (Base64 payload), `filename`. Optional: `signal`, `topic`, `cell_id` (existing cell — creates a `related_to` tunnel). Always creates a new `pending` Cell whose content is the extracted text (or the filename if no text could be extracted); the Classifier agent enriches the cell asynchronously. Stores original in SeaweedFS, generates JPEG thumbnail at ingest. Returns `{ attachment_id, cell_id, mime_type, size_bytes, has_thumbnail }`. For large files, prefer `POST /api/attachments` (multipart).
31. `list_attachments`: List all file attachments linked to a cell (metadata only, no file content).
32. `get_attachment_info`: Get metadata for a single attachment by ID. Return fields include `cell_id` (UUID of the extraction cell), `content_uri` (`hivemem://attachments/{id}/content`), and `thumbnail_uri` (`hivemem://attachments/{id}/thumbnail` or null). Download via `GET /api/attachments/{id}/content`.

**Admin (2):**

33. `approve_pending`: Admin tool to batch approve or reject agent writes.
34. `health`: Monitor DB and service state.

## Search Signals

The `search` tool combines 6 signals with configurable weights:

| Signal | Default Weight | Description |
|---|---|---|
| Semantic | 0.30 | Vector cosine similarity |
| Keyword | 0.15 | PostgreSQL full-text search (tsvector, BM25-like) |
| Recency | 0.15 | Exponential decay, 90-day half-life |
| Importance | 0.15 | User/agent assigned 1-5 scale |
| Popularity | 0.15 | Access frequency (materialized view) |
| Graph proximity | 0.10 | Boost for cells reachable from the top semantic candidates via tunnels (depth ≤ 2). Per-relation weights default to `builds_on=1.0`, `refines=0.8`, `related_to=0.6`, `contradicts=0.4`. |

Weights are configurable via `hivemem.search.weights` in `application.yml` and per-call via the MCP `search` arguments (`weight_semantic`, `weight_keyword`, `weight_recency`, `weight_importance`, `weight_popularity`, `weight_graph_proximity`).

`search` defaults to `summary`, `tags`, `importance`, and `created_at` plus required identity fields (`id`, `realm`, `signal`, `topic`). `get_cell` defaults to `summary`, `key_points`, `insight`, `tags`, `importance`, `source`, and `created_at` plus the same required identity fields. Pass `include` to request a specific subset of optional fields, including `content`.

## Progressive Summarization

Every cell supports four progressive fields:

| Field | Purpose |
|---|---|
| `content` | Full verbatim text |
| `summary` | One-sentence summary for scanning |
| `key_points` | 3-5 core takeaways |
| `insight` | Personal conclusion / implication |

Plus `actionability` (actionable / reference / someday / archive) and `importance` (1-5).
