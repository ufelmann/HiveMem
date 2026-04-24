## HiveMem 7.1.0

Ranked search now uses the HNSW index end-to-end. The 7.0.0 notes mentioned
this as a future follow-up; it is done.

### Highlights

- **HNSW index is built on first run** (not only on model swap).
  `EmbeddingMigrationService` now calls `createEmbeddingIndex(dimension)` on
  the first-run path and as a self-healing safety net on every matching-model
  restart. Operators who dropped the index or deployments that predate this
  behaviour get the index on the next container start.

- **`ranked_search()` actually uses HNSW.**
  Two migrations:
  - **V0014** rewrites the function as `LANGUAGE SQL STABLE` so PostgreSQL
    inlines it into the calling query. PL/pgSQL bodies are opaque to the outer
    planner — inlining unlocks both cross-boundary index optimization and
    proper EXPLAIN visibility for regression tests.
  - **V0015** splits the function into a prefilter + rerank pattern:
    - `ann` CTE — top-200 by cosine distance using the
      `(embedding::vector(1024)) <=> query_embedding` expression, which matches
      the HNSW expression index.
    - `kw` CTE — rows matching `plainto_tsquery('simple', …)`, capped at 200,
      served by the `idx_cells_tsv` GIN index.
    - Candidate set is the UNION of both (≤ 400 rows); the 5-signal rescoring
      runs only over candidates, with the V0013 hard filter and V0012 tiebreak
      unchanged.

### Behaviour change

- Scoring now runs over the candidate set (≤ 400 rows per call) rather than
  every committed cell. Top-K quality is equivalent for reasonable queries;
  recall beyond 400 would require tuning the per-CTE LIMIT.

### Known limitation

The embedding cast dimension in `ranked_search` is hardcoded to 1024 (the
production default). If you swap to a model with a different dimension,
`EmbeddingMigrationService` will rebuild the HNSW index and re-encode rows,
but the function still casts to 1024 and will fall back to seq scan until
the function is regenerated. Follow-up: extend the migration service to also
regenerate `ranked_search()` with the active dim (or parameterize it).

### Upgrade

```bash
docker pull ghcr.io/ufelmann/hivemem:7.1.0
docker stop hivemem && docker rm hivemem
docker run -d --name hivemem \
  --network hivemem-net -p 8421:8421 \
  --security-opt apparmor=unconfined \
  -e HIVEMEM_JDBC_URL=... -e HIVEMEM_DB_USER=... -e HIVEMEM_DB_PASSWORD=... \
  -e HIVEMEM_EMBEDDING_URL=http://hivemem-embeddings:80 \
  --restart unless-stopped \
  ghcr.io/ufelmann/hivemem:7.1.0
```

No breaking changes vs 7.0.0; MCP tool names are unchanged.
