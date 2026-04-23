## HiveMem 6.3.0

This release introduces include-based field selection for cell retrieval and
fully parametrizes the embedding service through environment variables. The
MCP surface keeps its 30 tools; only the field shape of `hivemem_get_cell` and
`hivemem_search` responses changed (additive, opt-in).

### Highlights

- **Include-based field selection for `hivemem_get_cell` and `hivemem_search`.**
  - Both tools accept an optional `include` array listing which non-required
    fields to return. Required fields (`id`, `realm`, `signal`, `topic`) are
    always included; everything else is opt-in.
  - `hivemem_search` defaults to `summary`, `tags`, `importance`, `created_at` —
    noticeably cheaper per response than returning full `content` for every hit.
  - `hivemem_get_cell` defaults keep the pre-6.3 workflow-critical fields
    (`summary`, `key_points`, `insight`, `tags`, `importance`, `source`,
    `actionability`, `status`, `created_at`). `content`, `valid_from`,
    `valid_until`, `parent_id`, and `created_by` are available via `include`
    when needed.
  - The SELECT projection for `findCell` only materializes columns that were
    actually requested (and `NULL::type AS <col>` for the rest), shrinking
    wire-time cost for narrow lookups.
- **Embedding service fully env-configurable.**
  - New knobs: `MODEL_PATH`, `MODEL_NAME`, `ONNX_FILE`, `POOLING`,
    `MAX_LENGTH`, `QUERY_PREFIX`, `DOCUMENT_PREFIX`, `MODEL_REPO`,
    `HF_ALLOW_PATTERNS`.
  - The service auto-detects ONNX + tokenizer files from the configured path
    and only sends `token_type_ids` when the loaded ONNX graph needs it —
    BGE-M3 (XLM-RoBERTa, two inputs) and e5/MiniLM (BERT, three inputs) both
    work without code changes.
  - Runtime no longer pulls `optimum` or `sentence-transformers`; the image
    shrinks accordingly.
  - New `test_app_onnx.py` covers the configuration plumbing end-to-end.
- **Centralized `include` field catalogue.**
  - `CellFieldSelection.searchIncludeFields()` and
    `getCellIncludeFields()` expose the canonical lists that the tool handlers
    consume, eliminating the previously duplicated arrays.

### Breaking Changes from 6.2.x

None. Callers that did not pass `include` see the same field set
`hivemem_get_cell` returned in 6.2, and `hivemem_search` continues to return
the same required metadata plus the default optional set. The four fields
(`parent_id`, `actionability`, `status`, `created_by`) that an interim
development version temporarily dropped are fully available again.

### Migration from 6.2.x

1. `docker exec hivemem hivemem-backup`
2. `docker pull ghcr.io/ufelmann/hivemem:6.3.0`
3. `docker pull ghcr.io/ufelmann/hivemem-embeddings:6.3.0`
4. Restart the stack with the new tags. No schema migrations are needed.
5. Optionally, start requesting `content` only via `include` in search
   payloads to reduce token/bandwidth cost on search-heavy workloads.

#### Switching embedding models

If you want to use a different local ONNX embedding model, set the new env
vars in `.env` before starting:

```
MODEL_PATH=/app/local-models/<name>
MODEL_NAME=<display-name>
ONNX_FILE=<relative path to .onnx inside MODEL_PATH>
POOLING=cls|mean
QUERY_PREFIX=
DOCUMENT_PREFIX=
```

Wipe the `hivemem_hivemem-pgdata` volume before restarting — dimension and
model name are frozen at first run.

### Docker

```bash
docker pull ghcr.io/ufelmann/hivemem:6.3.0
docker pull ghcr.io/ufelmann/hivemem-embeddings:6.3.0
```

Use `:main` only if you explicitly want the rolling branch build.

### Full Changelog

https://github.com/ufelmann/HiveMem/compare/v6.2.0...v6.3.0
