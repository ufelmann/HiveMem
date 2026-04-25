# HiveMem 7.1.1

## Fixes

- `ranked_search` SQL function is now created at startup by `EmbeddingMigrationService` with the active embedding dimension reported by the embedding service, instead of being hardcoded to `vector(1024)` in Flyway migrations V0014/V0015. Production deployments using non-default embedding models (e.g. `paraphrase-multilingual-MiniLM-L12-v2` at 384 dimensions) no longer fail every search call with `expected 1024 dimensions, not N`.

## Internal

- New SQL template `db/templates/ranked_search.sql.tmpl` with `{{DIM}}` placeholder.
- New `RankedSearchTemplate` loader and `EmbeddingStateRepository.replaceRankedSearchFunction(int)`.
- V0017 migration drops the previously Flyway-owned `ranked_search`; the service is now sole owner.
- Function is recreated on every startup path (first-run, match, post-reencode), parallel to existing HNSW index handling.
