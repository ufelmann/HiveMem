## HiveMem 6.1.0

Completes the terminology rename started in 6.0.0: two array columns inside
the `blueprints` table and their corresponding MCP API fields now use the new
vocabulary. Also ships the `mock.ts` snapshot sanitisation, the AppArmor run
flag, and the remaining UI rename stragglers landed since 6.0.1.

### Highlights

- **`V0011__blueprint_column_rename.sql`** — idempotent migration that renames:
  - `blueprints.hall_order` → `signal_order`
  - `blueprints.key_drawers` → `key_cells`
  - Also drops and recreates the `active_blueprints` view so it exposes the
    new column names.
- **MCP API surface matches the terminology.** The new field names were
  documented in `README.md` since 6.0.0; the implementation now matches.
- **Housekeeping** carried over from 6.0.1:
  - `knowledge-ui/src/data/mock.ts` replaced with synthetic demo data — no
    real production content leaks through the mock client any more.
  - Run command now sets `--security-opt apparmor=unconfined` on a container to
    avoid the Java NIO `UnixDispatcher: Permission denied` failure on
    unprivileged LXC.
  - UI rename stragglers fixed: `IconRail` panel id `'wings'` → `'realms'`,
    `goldbergMath.assignWings` → `assignRealms`, `wingPalette.ts` →
    `realmPalette.ts`.

### Breaking Changes from 6.0.x

Clients that called `update_blueprint` with the old argument names or
consumed `get_blueprint` output fields must switch to the new names:

| Old | New |
|---|---|
| `hall_order` | `signal_order` |
| `key_drawers` | `key_cells` |

No other MCP tool signatures changed.

### Migration from 6.0.x

1. `docker exec hivemem hivemem-backup`
2. `docker pull ghcr.io/ufelmann/hivemem:6.1.0`
3. Restart with the new image — Flyway runs `V0011` automatically. The
   migration is idempotent and safe to re-run.
4. Update any client code that calls `update_blueprint` or reads
   `get_blueprint` output to use `signal_order` / `key_cells`.

### Docker

```bash
docker pull ghcr.io/ufelmann/hivemem:6.1.0
```

### Full Changelog

https://github.com/ufelmann/HiveMem/compare/v6.0.1...v6.1.0
