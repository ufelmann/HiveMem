# HiveMem

Personal knowledge system -- MCP server with PostgreSQL semantic search and temporal knowledge graph.

## Index

```
.:{Dockerfile,Dockerfile.base,Dockerfile.testdb,docker-compose.yml,deploy.sh,entrypoint.sh,pyproject.toml,.env.example,.gitignore,.dockerignore}
hivemem:{__init__.py,server.py,db.py,embeddings.py,models.py,schema.sql,security.py}
hivemem/tools:{__init__.py,read.py,write.py,import_tools.py,admin.py}
tests:{conftest.py,test_db.py,test_embeddings.py,test_read.py,test_write.py,test_import.py,test_server.py,test_schema_v2.py,test_ranked_search.py,test_progressive_summarization.py,test_agent_fleet.py,test_references.py,test_blueprints.py,test_graph_search.py,test_integration.py,test_token_management.py,test_http_integration.py,test_token_performance.py,test_sql_robustness.py,test_security.py,test_concurrency.py,test_tunnels_migration.py,test_migrations.py}
migrations:{0001_initial.sql,0002_tunnels_v2.sql}
scripts:{seed-identity.py,hivemem-backup,hivemem-token,hivemem-migrate}
docs/superpowers/specs:{*.md} (local only, gitignored)
docs/superpowers/plans:{*.md} (local only, gitignored)
```

## Context

- **Stack:** Python 3.11+, FastMCP, PostgreSQL 17 + pgvector + Apache AGE, BGE-M3 (FlagEmbedding), Docker
- **Architecture:** Single Docker container (PG + MCP Server), shell entrypoint, 4-layer ASGI middleware
- **DB:** PostgreSQL via Unix socket (`/var/run/postgresql`), user `hivemem`, db `hivemem`, scram-sha-256 auth
- **MCP Server:** Streamable HTTP on port 8421 (direct, no reverse proxy)
- **Embeddings:** BGE-M3 (BAAI/bge-m3), 1024 dims, ~2.2GB RAM, multilingual (100+ languages)
- **Tables:** drawers, facts, tunnels, identity, agents, agent_diary, access_log, references_, drawer_references, blueprints, api_tokens (11 total)
- **Schema:** Append-only versioning (parent_id, valid_from/valid_until), status workflow (pending/committed/rejected)
- **Search:** 5-signal ranked search (semantic, keyword, recency, importance, popularity)
- **Auth:** DB-backed tokens with SHA-256 hashing, 4 roles, per-role tool visibility, 60s LRU cache (max 1000)

## How to Work in This Repo

- Read `hivemem/server.py` to understand tool registration and middleware stack
- All tools are async functions in `hivemem/tools/` -- server.py wraps them as MCP tools
- server.py has 4 ASGI middlewares: AuthMiddleware -> _AcceptMiddleware -> _IdentityMiddleware -> _ToolGateMiddleware -> mcp_app
- `created_by` is injected from the auth token, not passed by the client
- Agent-role tokens force `status: pending` on all writes
- `approve_pending` is admin-only
- Tests use testcontainers (Dockerfile.testdb) -- no deployment needed, just Docker + Python
- Match existing patterns: tools return dicts, use `fetch_one`/`fetch_all`/`execute` from `db.py`
- For write operations that need atomicity, use `async with pool.connection()` directly instead of the db helpers
- Embeddings are mocked in tests (word-hash based, no torch needed)
- HTTP integration tests use `httpx.AsyncClient` with ASGI transport + `_LifespanASGIWrapper` (see `test_http_integration.py`)
- New schema changes: create `migrations/NNNN_description.sql` with `-- depends: previous_migration` header

## Commands

- **test:** `pytest tests/ -v`
- **test single:** `pytest tests/test_token_management.py -v`
- **test with output:** `pytest tests/test_token_performance.py -v -s`
- **build:** `docker build -t hivemem .`
- **run:** `docker run -d --name hivemem -p 8421:8421 -v hivemem_data:/data -v hivemem_models:/data/models --security-opt apparmor=unconfined --restart unless-stopped hivemem`
- **deploy:** `./deploy.sh` (auto-detects base image changes)
- **backup:** `docker exec hivemem hivemem-backup`
- **backup cron:** `crontab on CT 102: 45 1 * * * docker exec hivemem hivemem-backup` (daily pg_dump at 01:45, before vzdump at 02:00)
- **token create:** `docker exec hivemem hivemem-token create <name> --role admin|writer|reader|agent [--expires 90d]`
- **token list:** `docker exec hivemem hivemem-token list`
- **token revoke:** `docker exec hivemem hivemem-token revoke <name>`
- **token info:** `docker exec hivemem hivemem-token info <name>`
- **logs:** `docker logs hivemem --tail 50`
- **psql:** `docker exec -it hivemem psql -U hivemem`
- **migrate:** `docker exec hivemem python3 /app/scripts/hivemem-migrate` (runs automatically on start)
- **seed:** `docker exec hivemem python3 scripts/seed-identity.py`

## Rules

- English code and comments
- Conventional Commits: `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`
- All tools are async -- use `async def` and `await`
- Tests use pytest-asyncio with `asyncio_mode = "auto"` (pyproject.toml)
- Tests use testcontainers -- no external DB needed
- DB helpers: `fetch_one`, `fetch_all`, `execute` from `hivemem.db` -- but `fetch_one` does NOT commit, `execute` does
- For atomic multi-statement writes, use `async with pool.connection()` with explicit `await conn.commit()`
- Embeddings: `encode(text)` for storage, `encode_query(text)` for search (lazy import, no torch at module load)
- `json_response=True` + `stateless_http=True` on FastMCP for Claude Code compatibility
- Bearer token auth on all requests (security.py) -- tokens stored as SHA-256 hashes in `api_tokens` table
- Append-only data model: never UPDATE content, use revise_drawer/revise_fact
- PL/pgSQL revise functions use `UPDATE ... RETURNING *` for atomicity -- no separate SELECT
- All queries with potentially large results must have a LIMIT parameter
- Recursive CTEs must have a depth cap (max 100)
- `approve_pending` uses batch `WHERE id = ANY()`, not per-item loops
- `approve_pending` validates `decision` against allowlist (`committed`, `rejected`)
- `mine_file`/`mine_directory` only accept paths under `/data/imports` or `/tmp` (path traversal protection)
- `_ToolGateMiddleware` enforces permissions on both `tools/list` (filtering) AND `tools/call` (403 rejection)
- `_ToolGateMiddleware` skips SSE responses (text/event-stream) to avoid Content-Length conflicts
- `log_access` `accessed_by` is injected from token, not client-controlled
- Default identity is `reader`, not `admin` (fail-safe for unauthenticated code paths)
- No `X-Forwarded-For` trust (prevents rate limit bypass via header spoofing)
- Pool init uses `asyncio.Lock` (prevents double-init across await points)
- Model loading uses `threading.Lock` (safe under multi-worker uvicorn)
- `update_blueprint` uses `pg_advisory_xact_lock` to serialize concurrent updates per wing
- Dict `del` operations use `.pop(key, None)` to avoid KeyError under concurrency
- Schema changes go in `migrations/` as numbered SQL files with `-- depends:` yoyo headers
- `schema.sql` is reference documentation only — never executed directly
- Migrations must be idempotent where possible (`IF NOT EXISTS`, `CREATE OR REPLACE`, `DO $$ IF EXISTS $$`)
- `entrypoint.sh` runs `hivemem-migrate` on every container start — automatic backup before migration

## Auth & Roles

4 roles control tool visibility and write behavior:

| Role | Sees | Writes as | approve_pending |
|---|---|---|---|
| admin | all 38 tools | committed | yes |
| writer | 34 (no admin tools) | committed | no |
| reader | 17 (read-only) | can't write | no |
| agent | 34 (same as writer) | pending | no |

Admin-only tools: `approve_pending`, `health`, `log_access`, `refresh_popularity`

Token functions in `security.py`: `create_token`, `validate_token`, `list_tokens`, `revoke_token`, `get_token_info`

Role sets in `security.py`: `READ_TOOLS`, `WRITE_TOOLS`, `ADMIN_TOOLS`, `ALL_TOOLS`, `ROLE_TOOLS`

Permission check: `check_tool_permission(role, tool_name)` returns error string or None

Tool filtering: `filter_tools_for_role(role, tools)` filters tools/list responses

CLI: `scripts/hivemem-token` (Python, argparse, async)

## MCP Tools (38, role-filtered)

### Read (17 tools, visible to all roles)
| Tool | Description |
|---|---|
| `hivemem_status` | Counts, wings list, pending count, last activity (2 queries, consolidated) |
| `hivemem_search` | 5-signal ranked search with configurable weights |
| `hivemem_search_kg` | ILIKE search on active facts (has limit param, default 100) |
| `hivemem_get_drawer` | Single drawer by UUID with all L0-L3 layers |
| `hivemem_list_wings` | Wings with hall/drawer counts |
| `hivemem_list_halls` | Halls within a wing |
| `hivemem_traverse` | Bidirectional drawer-to-drawer graph traversal, UNION dedup, optional relation filter |
| `hivemem_time_machine` | Facts valid at a point in time (has limit param, default 100) |
| `hivemem_wake_up` | L0 identity + L1 critical facts for session start |
| `hivemem_drawer_history` | Revision chain via recursive CTE (depth cap 100) |
| `hivemem_fact_history` | Revision chain via recursive CTE (depth cap 100) |
| `hivemem_pending_approvals` | Pending agent suggestions |
| `hivemem_quick_facts` | All active facts about an entity |
| `hivemem_get_blueprint` | Blueprint for a wing |
| `hivemem_reading_list` | Unread/in-progress references |
| `hivemem_list_agents` | Registered agents |
| `hivemem_diary_read` | Agent diary entries |

### Write (17 tools, visible to writer/agent/admin)
| Tool | Description |
|---|---|
| `hivemem_add_drawer` | Store with L0-L3, created_by from token, agent forces pending |
| `hivemem_kg_add` | Fact triple, created_by from token, agent forces pending |
| `hivemem_kg_invalidate` | Set valid_until on a fact |
| `hivemem_revise_drawer` | Atomic: UPDATE...RETURNING + INSERT in one PL/pgSQL call |
| `hivemem_revise_fact` | Atomic: same pattern as revise_drawer |
| `hivemem_check_contradiction` | Active facts with same subject+predicate but different object |
| `hivemem_check_duplicate` | Vector similarity > threshold (HNSW-friendly, single distance compute) |
| `hivemem_update_identity` | UPSERT wake-up identity layer |
| `hivemem_update_blueprint` | Atomic: close old + insert new in single connection |
| `hivemem_add_reference` | Source reference (article, paper, book, etc.) |
| `hivemem_link_reference` | Link reference to drawer |
| `hivemem_add_tunnel` | Drawer-to-drawer link with relation type, agent forces pending |
| `hivemem_remove_tunnel` | Soft-delete tunnel (sets valid_until) |
| `hivemem_register_agent` | Register or update agent |
| `hivemem_diary_write` | Agent diary entry |
| `hivemem_mine_file` | Import file as drawer |
| `hivemem_mine_directory` | Batch import directory |

### Admin (4 tools, admin-only)
| Tool | Description |
|---|---|
| `hivemem_approve_pending` | Batch approve/reject with `WHERE id = ANY()` |
| `hivemem_health` | DB connection, extensions, counts, disk |
| `hivemem_log_access` | Log access for popularity tracking |
| `hivemem_refresh_popularity` | Refresh materialized view |

## Tests (215)

| File | Count | What |
|---|---|---|
| `test_token_management.py` | 43 | Token CRUD, middleware, roles, filtering, E2E, SQL robustness |
| `test_http_integration.py` | 15 | Full HTTP: auth, tool visibility, tool execution, enforcement |
| `test_schema_v2.py` | 14 | Append-only, views, PL/pgSQL functions, constraints |
| `test_read.py` | 14 | All read tools |
| `test_write.py` | 13 | All write tools incl. add_tunnel, remove_tunnel, approve tunnels |
| `test_integration.py` | 8 | Cross-feature (revise+summarization, agent pipeline, contradictions) |
| `test_tunnels_migration.py` | 7 | Tunnel schema constraints, FK, views, indexes |
| `test_agent_fleet.py` | 7 | Agent registration, pending/approve/reject, diary |
| `test_token_performance.py` | 7 | Cache 0.002ms, DB 0.65ms, 218 req/s, bulk create, indexed lookup |
| `test_graph_search.py` | 9 | quick_facts, UUID traverse, bidirectional, pending/removed filtering |
| `test_sql_robustness.py` | 6 | Batch approve, limits, atomic transactions, cycle-safe traverse |
| `test_ranked_search.py` | 6 | 5-signal search, weights, filters |
| `test_progressive_summarization.py` | 5 | L0-L3, actionability, duplicate check |
| `test_references.py` | 6 | References, reading list, linking |
| `test_blueprints.py` | 5 | Blueprints CRUD, append-only |
| `test_import.py` | 5 | File/directory import |
| `test_embeddings.py` | 5 | Mock embeddings, similarity, German |
| `test_server.py` | 2 | Tool registration, health |
| `test_security.py` | 20 | Path traversal, tool enforcement, decision validation, XFF, safe defaults |
| `test_concurrency.py` | 11 | Parallel writes, same-row revise, cache stampede, pool init, advisory locks |
| `test_migrations.py` | 5 | yoyo tracking, all applied, idempotent, final schema |
| `test_db.py` | 2 | Pool, basic CRUD |

## Key Design Decisions

1. **PostgreSQL over ChromaDB** -- One DB for embeddings (pgvector), graph (AGE/CTEs), temporal (native SQL), and backup (pg_dump/PITR)
2. **BGE-M3 over e5-small** -- Multilingual (DE+EN), 1024 dims, 8192 token context, ~800ms-1.5s on CPU
3. **Single container** -- `docker run` for deployment, PG + MCP in one image, shell entrypoint
4. **`json_response=True` + `stateless_http=True`** -- Required for Claude Code MCP compatibility
5. **ASGI Accept middleware** -- MCP protocol requires both `application/json` and `text/event-stream`
6. **Classification by LLM, not rules** -- LLM classifies wing/hall/room during archive, no regex
7. **Append-only data model** -- Never UPDATE content, use revise functions. History via parent_id chains. PL/pgSQL uses UPDATE...RETURNING for atomicity.
8. **5-signal ranked search** -- Semantic + keyword + recency + importance + popularity. MAX hoisted into CTE. Queries drawers directly (not view) for HNSW index compatibility.
9. **DB-backed tokens with roles** -- SHA-256 hashed in PostgreSQL, 4 roles with per-role tool visibility, admin-only approval, 60s LRU cache (max 1000 entries), rate limiting, audit log
10. **Testcontainers** -- 195 tests with ephemeral PG container, no deployment needed, CI-ready
11. **Atomic SQL** -- revise functions use UPDATE...RETURNING, approve_pending uses batch ANY(), update_blueprint wraps close+insert in single connection, create/revoke token catch DB exceptions instead of SELECT-then-write
