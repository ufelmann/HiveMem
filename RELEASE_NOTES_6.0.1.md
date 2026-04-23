## HiveMem 6.0.1

Terminology rename, SP1 Knowledge-UI, and bi-temporal schema.

Tag `v6.0.1` supersedes `v6.0.0`: it adds `V0010` legacy-value normalization that
prevents the Flyway migration from failing on databases that carry pre-rename
`hall` values outside the current signal enum. Use `v6.0.1` for all new deploys.

### Highlights

- **Terminology rename** (`V0010__terminology_rename.sql`):
  - `drawer` → `cell`, `wing` → `realm`, `hall` → `signal`, `room` → `topic`
  - Tables, columns, views, indexes, and PL/pgSQL functions renamed in a single
    transactional migration with preflight guard and row-count check.
  - Unchanged: `tunnel`, `blueprint`, `reference`, `fact`, `agent`, `agent_diary`,
    `identity`, `api_token`, `access_log`, embedding, auth role names.
  - MCP tool surface renamed to match (`hivemem_add_cell`, `hivemem_get_cell`,
    `hivemem_list_realms`, …). Tool count stays at 30.
- **SP1 Knowledge-UI** — 2D PixiJS sphere canvas frontend (`knowledge-ui/`):
  - Vue 3 + Pinia + Vite + PixiJS; d3-force layout with Poisson-disk placement.
  - Panels: realms, search, settings, scan (summary, key points, insight + tunnels + facts), reader.
  - Reader: markdown-it + KaTeX + highlight.js, PDF.js and `.eml` (postal-mime)
    tabs, fullscreen dialog.
  - Interactions: wheel zoom, drag pan, snap-focus tween, double-click opens
    reader, Cmd+K / Esc / Enter / `?` keybindings, live-update reload toast.
  - Playwright e2e smoke (login → search → open → reader → close).
- **Bi-temporal schema** (`V0009__bi_temporal.sql`): adds `ingested_at` so facts
  carry event time (`valid_from`) and transaction time (`ingested_at`).

### Fix in 6.0.1

- `V0010`: normalize legacy `hall` values to the current signal enum before the
  CHECK constraint is applied. Without this, migration fails on older data sets.

### Breaking Changes from 5.x

- **Schema rename is non-reversible.** Back up before upgrading.
- **API rename:** all tool names and most JSON fields changed. Clients using the
  old names (`get_drawer`, `list_wings`, `hall`, `room`, `wing`, `drawer_id`…)
  must be updated.
- **Frontend replaced.** The old `memory-palace/` scaffold is gone; the new
  frontend lives in `knowledge-ui/`.

### Migration from 5.x

1. `docker exec hivemem hivemem-backup`
2. `docker pull ghcr.io/ufelmann/hivemem:6.0.1`
3. Restart the container — Flyway runs `V0010` automatically. Preflight aborts
   cleanly if the `drawers` table is already gone or `cells` already exists.
4. Update any consumer that calls the old MCP tool or field names.

### Docker

```bash
docker pull ghcr.io/ufelmann/hivemem:6.0.1
```

Note: `:6.0.0` exists but lacks the `V0010` legacy-value normalization. Prefer
`:6.0.1` or `:latest`.

### Full Changelog

https://github.com/ufelmann/HiveMem/compare/v5.0.0...v6.0.1
