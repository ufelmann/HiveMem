## HiveMem 3.1.0

Complete rewrite from Python to Java. Production-verified against migrated 2.x data.

### Highlights

- **Spring Boot 3.3.5** + jOOQ + Flyway + Caffeine + Testcontainers
- **250 tests**, all green (CI + Codecov integrated)
- **Full MCP protocol** — initialize, SSE stream, notifications, tools/list + tools/call
- **Token CRUD API** — createToken, listTokens, revokeToken, getTokenInfo
- **7 Flyway migrations** (V0001–V0007), idempotent baseline-on-migrate from 2.x data
- **PostgreSQL 17** + pgvector (external DB, not bundled)
- **External embeddings** via HTTP client (BGE-M3 or any compatible service)
- **4 auth roles** — admin, writer, reader, agent (38 tools, role-filtered)

### Breaking Changes from 2.x

- **Python removed entirely** — no more `hivemem/`, `tests/`, `pyproject.toml`, `Dockerfile.base`
- **External PostgreSQL required** — the 2.x single-container (PG + server) pattern is gone. Run `pgvector/pgvector:pg17` separately.
- **External embeddings required** — BGE-M3 no longer runs in-process. Set `HIVEMEM_EMBEDDING_URL`.
- **Environment variables changed** — `HIVEMEM_JDBC_URL`, `HIVEMEM_DB_USER`, `HIVEMEM_DB_PASSWORD`, `HIVEMEM_EMBEDDING_URL` (see README)
- **Apache AGE removed** — was never used; graph traversal uses recursive CTEs on the `tunnels` table

### Migration from 2.x

1. Backup: `docker exec hivemem hivemem-backup`
2. Start pgvector/pgvector:pg17, load the sanitized dump (strip `ag_catalog` lines)
3. `docker run ghcr.io/ufelmann/hivemem:3.1.0` with the four env vars
4. Flyway auto-migrates (baseline-on-migrate); existing API tokens stay valid (same SHA-256)

### Docker

```bash
docker pull ghcr.io/ufelmann/hivemem:3.1.0
```

### Security Fixes

- SQL injection in `hivemem-token` CLI (psql variable binding instead of string interpolation)
- `mine_file`/`mine_directory` moved from admin-only to writer/agent/admin (spec compliance)
- `@JsonInclude(NON_NULL)` on MCP responses (strict JSON-RPC 2.0 compliance)
- `OffsetDateTime.now(ZoneOffset.UTC)` for token expiry comparison

### Full Changelog

https://github.com/ufelmann/HiveMem/compare/v2.1.0...v3.1.0
