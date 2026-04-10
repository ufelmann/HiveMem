# HiveMem

Personal knowledge system -- MCP server with PostgreSQL semantic search and temporal knowledge graph.

## Index

```
.:{docker-compose.yml,Dockerfile,Dockerfile.db,pyproject.toml,.env.example,.gitignore}
hivemem:{__init__.py,server.py,db.py,embeddings.py,models.py,schema.sql}
hivemem/tools:{__init__.py,read.py,write.py,import_tools.py,admin.py}
tests:{conftest.py,test_db.py,test_embeddings.py,test_read.py,test_write.py,test_import.py,test_server.py}
scripts:{backup.sh,seed-identity.py}
skills:{archive.md,wakeup.md}
caddy:{Caddyfile}
backups:{*.sql.gz}
```

## Context

- **Stack:** Python 3.11+, FastMCP, PostgreSQL 17 + pgvector + Apache AGE, BGE-M3 (FlagEmbedding), Docker Compose, Caddy
- **Architecture:** Docker Compose stack (db, mcp, caddy)
- **DB:** PostgreSQL at localhost:5432, user `hivemem`, db `hivemem`
- **MCP Server:** Streamable HTTP on port 8420 (internal), Caddy on port 8421 (external)
- **Embeddings:** BGE-M3 (BAAI/bge-m3), 1024 dims, ~2.2GB RAM, multilingual (100+ languages)
- **Tables:** drawers (content + vector), facts (KG triples + temporal), edges (graph), identity (wake-up layers)

## How to Work in This Repo

- Read `hivemem/server.py` to understand tool registration pattern
- All tools are async functions in `hivemem/tools/` -- server.py wraps them as MCP tools
- Tests use real PostgreSQL (not mocks) -- DB must be running for tests
- Match existing patterns: tools return dicts, use `fetch_one`/`fetch_all`/`execute` from `db.py`

## Commands

- **build:** `docker compose build`
- **test:** `cd /root/hivemem && source .venv/bin/activate && pytest tests/ -v`
- **run:** `docker compose up -d`
- **backup:** `./scripts/backup.sh`
- **logs:** `docker compose logs mcp --tail 50`
- **seed:** `python scripts/seed-identity.py`

## Rules

- English code and comments
- Conventional Commits: `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`
- All tools are async -- use `async def` and `await`
- Tests use pytest-asyncio with `asyncio_mode = "auto"` (pyproject.toml)
- DB helpers: `fetch_one`, `fetch_all`, `execute` from `hivemem.db`
- Embeddings: `encode(text)` for storage, `encode_query(text)` for search
- `json_response=True` + `stateless_http=True` on FastMCP for Claude Code compatibility
- Caddy overwrites `Accept` header (`application/json, text/event-stream`) for MCP protocol

## MCP Tools (16)

### Read (9)
| Tool | Description |
|---|---|
| `hivemem_status` | Palace overview: wings, counts, last activity |
| `hivemem_search` | Semantic vector search over drawers |
| `hivemem_search_kg` | ILIKE search on KG facts (subject/predicate/object) |
| `hivemem_get_drawer` | Get single drawer by UUID |
| `hivemem_list_wings` | List all wings with room/drawer counts |
| `hivemem_list_rooms` | List rooms within a wing |
| `hivemem_traverse` | Recursive multi-hop graph traversal from entity |
| `hivemem_time_machine` | Query facts valid at a specific point in time |
| `hivemem_wake_up` | Load L0 identity + L1 critical facts for session start |

### Write (4)
| Tool | Description |
|---|---|
| `hivemem_add_drawer` | Store raw text with embedding and classification |
| `hivemem_kg_add` | Add a knowledge graph fact triple |
| `hivemem_kg_invalidate` | Mark a fact as outdated (set valid_until) |
| `hivemem_update_identity` | UPSERT wake-up identity layer |

### Import (2)
| Tool | Description |
|---|---|
| `hivemem_mine_file` | Import single file as a drawer |
| `hivemem_mine_directory` | Batch-import directory of files |

### Admin (1)
| Tool | Description |
|---|---|
| `hivemem_health` | DB connection, extensions, counts, disk usage |

## Skills

- **`skills/archive.md`** -- Archive session: summarize, classify (wing/room/hall), store drawer, extract KG facts, resolve contradictions
- **`skills/wakeup.md`** -- Session start: load L0+L1 identity, detect topic, progressive context loading

## Key Design Decisions

1. **PostgreSQL over ChromaDB** -- One DB for embeddings (pgvector), graph (AGE/CTEs), temporal (native SQL), and backup (pg_dump/PITR)
2. **BGE-M3 over e5-small** -- Multilingual (DE+EN), 1024 dims, 8192 token context, ~800ms-1.5s on CPU
3. **Docker Compose** -- `docker compose up` for deployment, designed for open-source release
4. **`json_response=True` + `stateless_http=True`** -- Required for Claude Code MCP compatibility
5. **Caddy Accept header overwrite** -- MCP protocol requires both `application/json` and `text/event-stream`
6. **Classification by LLM, not rules** -- LLM classifies wing/room/hall during archive, no regex
7. **Raw text storage** -- No compression; summarize on-the-fly at load time
