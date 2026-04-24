## HiveMem 7.0.0

This release tightens the MCP tool surface, moves ranked search fully into
PostgreSQL, and adds a `reclassify_cell` tool. **Breaking for MCP clients**:
tool names drop the `hivemem_` prefix.

### Breaking changes

- **All MCP tool names lose the `hivemem_` prefix.** `hivemem_search` →
  `search`, `hivemem_wake_up` → `wake_up`, `hivemem_add_cell` → `add_cell`,
  and so on. External MCP clients must update their tool invocations.
- **`search` result shape is stricter.** The hard filter
  `semantic > 0.3 OR keyword > 0` now runs in SQL, so cells with neither a
  semantic nor a keyword match are excluded (the old in-memory path returned
  them). Empty result lists are a legitimate outcome.
- **`search` keyword scoring uses `plainto_tsquery` AND-semantics.** Queries
  now require all terms to match the cell, not just one — "semantic oracle"
  no longer matches a cell that only contains "oracle".

### Highlights

- **Ranked search moved into SQL (`ranked_search()`).**
  `ReadToolService.search` no longer loads all candidate cells into Java to
  score them in-process. It calls the existing `ranked_search()` PL/pgSQL
  function via jOOQ. V0012 extends the function's return shape with
  `valid_until` and adds `ORDER BY score_total DESC, id ASC` for a
  deterministic tiebreak. Popularity now consistently uses
  `cell_popularity.recent_access_count`.
- **tsv dictionary switched from `'english'` to `'simple'`.**
  HiveMem content is DE+EN mixed. The previous config applied English
  stemming/stopwords, which misindexed German tokens. V0013 drops and
  recreates `cells.tsv` (and the dependent `active_cells` / `realm_stats`
  views) with the new expression and updates `ranked_search()` in lockstep.
  Trade-off: English plurals no longer match singulars (`cells` ≠ `cell`);
  embedding similarity still covers conceptual matches.
- **New `reclassify_cell` tool.** Move a cell to a different realm/signal/
  topic without a full revise cycle.
- **`wake_up` returns all identity keys.** The handler no longer filters by
  a hardcoded allow-list; any key written to the `identity` table is
  surfaced under `context`. The outer envelope still carries
  `{identity, role}` from the 6.4.0 fix.
- **Node base image bumped to `node:25-alpine`.**
- **Maven build image bumped to `maven:3-eclipse-temurin-26`.**
- **Spring Boot bumped to 4.0.6.**

### Migrations

Two forward-only migrations run automatically on startup:

- `V0012__ranked_search_extend.sql` — extends `ranked_search()` return shape
  and tiebreak; recreates the function.
- `V0013__tsv_dictionary_simple.sql` — drops/recreates the `tsv` generated
  column with the `'simple'` dictionary, recreates dependent views and the
  function. The tsv is re-materialized over the existing row set; this is
  a one-time cost proportional to the size of `cells`.

### Operational notes

- No HNSW index yet — the embedding column keeps the variable-dim
  loosening from V0008. A follow-up will add an HNSW index once the active
  embedding dimensionality is pinned.
- MCP clients (including Claude Code configs and any agent SDK wiring)
  must switch to the new tool names on upgrade.

### Upgrade

```bash
docker pull ghcr.io/ufelmann/hivemem:7.0.0
docker stop hivemem && docker rm hivemem
docker run -d --name hivemem \
  --network hivemem-net -p 8421:8421 \
  --security-opt apparmor=unconfined \
  -e HIVEMEM_JDBC_URL=... -e HIVEMEM_DB_USER=... -e HIVEMEM_DB_PASSWORD=... \
  -e HIVEMEM_EMBEDDING_URL=http://hivemem-embeddings:80 \
  --restart unless-stopped \
  ghcr.io/ufelmann/hivemem:7.0.0
```
