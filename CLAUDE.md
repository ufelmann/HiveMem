# HiveMem

Personal knowledge system -- MCP server with PostgreSQL semantic search and temporal knowledge graph.

## Index

```
.:{Dockerfile,docker-compose.yml,deploy.sh,entrypoint.sh,.env.example,.gitignore,.dockerignore}
java-server:{pom.xml,mvnw,mvnw.cmd,.mvn/}
java-server/src/main/java/com/hivemem:{HiveMemApplication.java}
java-server/src/main/java/com/hivemem/auth:{AuthFilter,AuthPrincipal,AuthRole,CachedTokenService,DbTokenService,RateLimiter,TokenService,TokenServiceConfig,TokenSummary,ToolPermissionService}.java
java-server/src/main/java/com/hivemem/mcp:{McpController,McpRequest,McpResponse,ToolHandler,ToolRegistry}.java
java-server/src/main/java/com/hivemem/drawers:{DrawerReadRepository}.java
java-server/src/main/java/com/hivemem/search:{DrawerSearchRepository,KgSearchRepository}.java
java-server/src/main/java/com/hivemem/embedding:{EmbeddingClient,EmbeddingInfo,EmbeddingMigrationService,EmbeddingProperties,EmbeddingStateRepository,HttpEmbeddingClient}.java
java-server/src/main/java/com/hivemem/security:{ImportPathValidator}.java
java-server/src/main/java/com/hivemem/write:{AdminToolRepository,AdminToolService,WriteArgumentParser,WriteToolRepository,WriteToolService}.java
java-server/src/main/java/com/hivemem/tools/read:{15 ToolHandler classes}.java
java-server/src/main/java/com/hivemem/tools/write:{13 ToolHandler classes}.java
java-server/src/main/java/com/hivemem/tools/admin:{HealthToolHandler}.java
java-server/src/main/resources:{application.yml}
java-server/src/main/resources/db/migration:{V0001__baseline.sql .. V0008__flexible_embedding_dimension.sql}
embedding-service:{app.py,Dockerfile,download_model.py}
java-server/src/test/java/com/hivemem:{27 test classes}
scripts:{hivemem-backup,hivemem-token,hivemem-migrate}
docs/superpowers/specs:{*.md} (local only, gitignored)
docs/superpowers/plans:{*.md} (local only, gitignored)
```

## Context

- **Stack:** Java 25, Spring Boot 4.0.5, jOOQ, Flyway, Caffeine, PostgreSQL + pgvector, Testcontainers, Docker
- **Architecture:** Multi-stage Docker build (Maven + eclipse-temurin:25-jre), shell entrypoint, Spring Boot web app with servlet filter chain
- **DB:** External PostgreSQL via JDBC (`HIVEMEM_JDBC_URL`), Flyway-managed schema (8 migrations)
- **MCP Server:** HTTP on port 8421 via `McpController` (JSON-RPC over `/mcp`)
- **Embeddings:** External HTTP service (`HIVEMEM_EMBEDDING_URL`), accessed via `HttpEmbeddingClient`. Model change detection via `EmbeddingMigrationService` (startup check, auto-reencoding) — auto-logging on `get_drawer` writes one row per read; popularity refresh handled by `PopularityRefreshScheduler` (Spring `@Scheduled`, hourly default)
- **Tables:** drawers, facts, tunnels, identity, agents, agent_diary, access_log, references_, drawer_references, blueprints, api_tokens (11 total)
- **Schema:** Append-only versioning (parent_id, valid_from/valid_until), status workflow (pending/committed/rejected)
- **Search:** 5-signal ranked search (semantic, keyword, recency, importance, popularity)
- **Auth:** DB-backed tokens with SHA-256 hashing, 4 roles, per-role tool visibility, Caffeine cache (60s TTL, max 1000)

## How to Work in This Repo

- Read `McpController.java` to understand HTTP routing and JSON-RPC dispatch
- Read `ToolRegistry.java` to see how tool handlers are registered
- Read `AuthFilter.java` for the servlet filter that does token validation, role injection, and rate limiting
- `ToolPermissionService` enforces per-role tool visibility on both `tools/list` and `tools/call`
- Each MCP tool is a `ToolHandler` implementation in `java-server/src/main/java/com/hivemem/tools/`
- `created_by` is injected from the auth token (`AuthPrincipal`), not passed by the client
- Agent-role tokens force `status: pending` on all writes
- `approve_pending` is admin-only
- DB access is via jOOQ DSLContext -- repositories in `drawers/`, `search/`, `write/`
- Tests use Testcontainers with `pgvector/pgvector:pg17` -- no external DB needed, just Docker
- Embeddings are stubbed in tests via `FixedEmbeddingClient` (deterministic vectors, no HTTP calls)
- Schema changes: add `java-server/src/main/resources/db/migration/V00NN__description.sql` (Flyway naming)
- `PopularityRefreshScheduler` runs hourly via `@Scheduled` — `@EnableScheduling` is on `HiveMemApplication`. Override interval via `hivemem.popularity.refresh-interval` (`PT1H` default).

## Commands

- **test:** `cd java-server && mvn test`
- **test single:** `cd java-server && mvn test -Dtest=ReadToolIntegrationTest`
- **test with output:** `cd java-server && mvn test -Dtest=TokenPerformanceTest -pl .`
- **build:** `docker build -t hivemem .`
- **run:** `docker run -d --name hivemem -p 8421:8421 -e HIVEMEM_JDBC_URL=... -e HIVEMEM_DB_USER=... -e HIVEMEM_DB_PASSWORD=... -e HIVEMEM_EMBEDDING_URL=... --restart unless-stopped hivemem`
- **deploy:** `docker exec hivemem hivemem-backup && ./deploy.sh java` (backup first, then build image, restart container, health-check `/mcp`)
- **backup:** `docker exec hivemem hivemem-backup`
- **backup cron:** `crontab on CT 102: 45 1 * * * docker exec hivemem hivemem-backup` (daily pg_dump at 01:45, before vzdump at 02:00)
- **token create:** `docker exec hivemem hivemem-token create <name> --role admin|writer|reader|agent [--expires 90d]`
- **token list:** `docker exec hivemem hivemem-token list`
- **token revoke:** `docker exec hivemem hivemem-token revoke <name>`
- **token info:** `docker exec hivemem hivemem-token info <name>`
- **logs:** `docker logs hivemem --tail 50`

## Rules

- English code and comments
- Conventional Commits: `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`
- All tool handlers implement `ToolHandler` interface
- Tests use JUnit 5 + Testcontainers -- no external DB needed
- DB access via jOOQ `DSLContext` -- repositories hold the queries, services hold the logic
- Bearer token auth on all requests (`AuthFilter`) -- tokens stored as SHA-256 hashes in `api_tokens` table
- Append-only data model: never UPDATE content, use revise_drawer/revise_fact
- PL/pgSQL revise functions use `UPDATE ... RETURNING *` for atomicity -- no separate SELECT
- All queries with potentially large results must have a LIMIT parameter
- Recursive CTEs must have a depth cap (max 100)
- `approve_pending` uses batch `WHERE id = ANY()`, not per-item loops
- `approve_pending` validates `decision` against allowlist (`committed`, `rejected`)
- `ToolPermissionService` enforces permissions on both `tools/list` (filtering) AND `tools/call` (403 rejection)
- `get_drawer` automatically writes an `access_log` row with `accessed_by` from the token, not client-controlled
- No `X-Forwarded-For` trust (prevents rate limit bypass via header spoofing)
- `update_blueprint` uses `pg_advisory_xact_lock` to serialize concurrent updates per wing
- Schema changes go in `java-server/src/main/resources/db/migration/` as Flyway versioned SQL files
- Migrations must be idempotent where possible (`IF NOT EXISTS`, `CREATE OR REPLACE`, `DO $$ IF EXISTS $$`)
- Flyway runs automatically at Spring Boot startup -- no manual migration command

## Auth & Roles

4 roles control tool visibility and write behavior:

| Role | Sees | Writes as | approve_pending |
|---|---|---|---|
| admin | all 30 tools | committed | yes |
| writer | 28 (no admin tools) | committed | no |
| reader | 15 (read-only) | can't write | no |
| agent | 28 (same as writer) | pending | no |

Admin-only tools: `approve_pending`, `health`

Token service: `TokenService` interface with `DbTokenService` (jOOQ) and `CachedTokenService` (Caffeine decorator)

Role enum: `AuthRole` (ADMIN, WRITER, READER, AGENT)

Permission check: `ToolPermissionService.isAllowed(role, toolName)`

Tool filtering: `ToolPermissionService.filterToolsForRole(role, tools)`

CLI: `scripts/hivemem-token` (bash + psql)

## MCP Tools (30, role-filtered)

### Read (15 tools, visible to all roles)
| Tool | Description |
|---|---|
| `hivemem_status` | Counts, wings list, pending count, last activity (2 queries, consolidated) |
| `hivemem_search` | 5-signal ranked search with configurable weights |
| `hivemem_search_kg` | ILIKE search on active facts (has limit param, default 100) |
| `hivemem_get_drawer` | Single drawer by UUID with all L0-L3 layers |
| `hivemem_list_wings` | Wings with hall/drawer counts; halls of one wing when `wing` is provided |
| `hivemem_traverse` | Bidirectional drawer-to-drawer graph traversal, UNION dedup, optional relation filter |
| `hivemem_time_machine` | Facts valid at a point in time (has limit param, default 100) |
| `hivemem_wake_up` | L0 identity + L1 critical facts for session start |
| `hivemem_history` | Trace revisions of a drawer or fact (type-dispatched, recursive CTE depth cap 100) |
| `hivemem_pending_approvals` | Pending agent suggestions |
| `hivemem_quick_facts` | All active facts about an entity |
| `hivemem_get_blueprint` | Blueprint for a wing |
| `hivemem_reading_list` | Unread/in-progress references |
| `hivemem_list_agents` | Registered agents |
| `hivemem_diary_read` | Agent diary entries |

### Write (13 tools, visible to writer/agent/admin)
| Tool | Description |
|---|---|
| `hivemem_add_drawer` | Store with L0-L3, created_by from token; optional `dedupe_threshold` runs an embedding-based dedupe gate using the same vector that would have been stored (one embedding call, not two) |
| `hivemem_kg_add` | Fact triple, created_by from token; optional `on_conflict` (`insert`\|`return`\|`reject`) gates against active facts with the same subject+predicate |
| `hivemem_kg_invalidate` | Set valid_until on a fact |
| `hivemem_revise_drawer` | Atomic: UPDATE...RETURNING + INSERT in one PL/pgSQL call |
| `hivemem_revise_fact` | Atomic: same pattern as revise_drawer |
| `hivemem_update_identity` | UPSERT wake-up identity layer |
| `hivemem_update_blueprint` | Atomic: close old + insert new in single connection |
| `hivemem_add_reference` | Source reference (article, paper, book, etc.) |
| `hivemem_link_reference` | Link reference to drawer |
| `hivemem_add_tunnel` | Drawer-to-drawer link with relation type, agent forces pending |
| `hivemem_remove_tunnel` | Soft-delete tunnel (sets valid_until) |
| `hivemem_register_agent` | Register or update agent |
| `hivemem_diary_write` | Agent diary entry |

### Admin (2 tools, admin-only)
| Tool | Description |
|---|---|
| `hivemem_approve_pending` | Batch approve/reject with `WHERE id = ANY()` |
| `hivemem_health` | DB connection, extensions, counts, disk |

## Tests (261)

| Class | Tests | What |
|---|---|---|
| `TokenManagementIntegrationTest` | 33 | Token CRUD, middleware auth, role mapping, tool filtering, E2E flows |
| `SecurityIntegrationTest` | 28 | Tool enforcement, decision validation, XFF, safe defaults, role-based rejection |
| `WriteToolsIntegrationTest` | 29 | All write tools incl. add_tunnel, remove_tunnel, approve tunnels, dedupe gate, on_conflict |
| `ReadToolIntegrationTest` | 22 | All read tools incl. auto-log and history |
| `ProgressiveSummarizationIntegrationTest` | 16 | L0-L3 layers, actionability constraints, duplicate check |
| `SchemaV2IntegrationTest` | 14 | Append-only versioning, views, PL/pgSQL functions, constraints |
| `ConcurrencyIntegrationTest` | 10 | Parallel writes, same-row revise, advisory locks |
| `GraphSearchIntegrationTest` | 10 | quick_facts, UUID traverse, bidirectional, pending/removed filtering |
| `BlueprintsIntegrationTest` | 7 | Blueprints CRUD, append-only versioning |
| `TokenPerformanceTest` | 7 | Cache latency, DB lookup, throughput, bulk create, indexed lookup |
| `AgentFleetIntegrationTest` | 7 | Agent registration, pending/approve/reject workflow, diary |
| `ReferencesIntegrationTest` | 7 | References, reading list, drawer linking |
| `McpControllerTest` | 14 | JSON-RPC dispatch, MCP protocol (initialize, SSE, notifications, ping) |
| `SqlRobustnessIntegrationTest` | 6 | Batch approve, query limits, atomic transactions, cycle-safe traversal |
| `AuthFilterTest` | 5 | Servlet filter auth, role injection, rate limiting |
| `SearchParityIntegrationTest` | 5 | 5-signal search, weight tuning, filters |
| `CrossFeatureParityIntegrationTest` | 5 | Cross-feature flows (revise + summarization, agent pipeline, contradictions) |
| `FlywayMigrationParityTest` | 4 | Flyway tracking, all applied, idempotent, final schema |
| `DbTokenServiceTest` | 4 | Token service unit tests |
| `ParitySmokeTest` | 2 | Role-tool permission surface smoke test |
| `EmbeddingMigrationIntegrationTest` | 11 | Startup detection, reencoding, progress, batching, flexible dims, advisory lock |
| `EmbeddingClientContractTest` | 6 | Embedding client interface contract, /info endpoint, dimension validation |
| `PopularityRefreshSchedulerTest` | 3 | Scheduler bean wiring + refresh execution + repository invocation |
| `HttpTokenLifecycleIntegrationTest` | 3 | Full HTTP stack: request to auth to MCP to PostgreSQL |
| `PostgresIntegrationTest` | 1 | Basic connection and CRUD |
| `HttpEmbeddingClientTest` | 1 | HTTP embedding client unit test |
| `HiveMemApplicationTest` | 1 | Spring Boot context loads |

## Key Design Decisions

1. **PostgreSQL over ChromaDB** -- One DB for embeddings (pgvector), graph (CTEs), temporal (native SQL), and backup (pg_dump/PITR)
2. **External embeddings service** -- Decoupled from the Java process; allows GPU offloading and independent scaling
3. **Spring Boot + jOOQ** -- Type-safe SQL, Flyway migrations, Caffeine caching, servlet filter chain for auth
4. **Multi-stage Docker build** -- Maven build in `maven:3.9.13-eclipse-temurin-25`, runtime on `eclipse-temurin:25-jre`
5. **Classification by LLM, not rules** -- LLM classifies wing/hall/room during archive, no regex
6. **Append-only data model** -- Never UPDATE content, use revise functions. History via parent_id chains. PL/pgSQL uses UPDATE...RETURNING for atomicity.
7. **5-signal ranked search** -- Semantic + keyword + recency + importance + popularity. MAX hoisted into CTE. Queries drawers directly (not view) for HNSW index compatibility.
8. **DB-backed tokens with roles** -- SHA-256 hashed in PostgreSQL, 4 roles with per-role tool visibility, admin-only approval, Caffeine cache (60s, 1000 entries), rate limiting, audit log
9. **Testcontainers** -- 261 tests with ephemeral `pgvector/pgvector:pg17` container, no deployment needed, CI-ready
10. **Atomic SQL** -- revise functions use UPDATE...RETURNING, approve_pending uses batch ANY(), update_blueprint wraps close+insert in single transaction
11. **Embedding change detection** -- `EmbeddingMigrationService` (ApplicationRunner) checks model name + dimension at startup via `/info` endpoint, auto-reencodes all drawers on mismatch, blocks search with 503 during reencoding, crashes on failure (fail-fast). Runtime dimension validation on every embedding call.
12. **Integrated dedupe / conflict gates** -- `add_drawer` accepts `dedupe_threshold` to check for near-duplicates using the same embedding it would have stored (one HTTP round-trip instead of two). `kg_add` accepts `on_conflict` (`insert`|`return`|`reject`) for the equivalent gate on fact triples. `log_access` runs automatically inside `get_drawer`; popularity MV refresh runs on a schedule (`PopularityRefreshScheduler`, default hourly via `@Scheduled`). This shrank the MCP tool surface from 38 to 30 without losing capability.
