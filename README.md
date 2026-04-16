# HiveMem

Personal knowledge system with semantic search, temporal knowledge graph, and progressive summarization.

MCP server backed by PostgreSQL (pgvector) with external embeddings service. 38 tools, append-only versioning, role-based token auth, agent fleet with approval workflow.

[![CI](https://github.com/ufelmann/HiveMem/actions/workflows/ci.yml/badge.svg)](https://github.com/ufelmann/HiveMem/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/ufelmann/HiveMem/graph/badge.svg)](https://codecov.io/gh/ufelmann/HiveMem)
[![GitHub release](https://img.shields.io/github/v/release/ufelmann/HiveMem)](https://github.com/ufelmann/HiveMem/releases)
[![GHCR](https://img.shields.io/badge/ghcr.io-ufelmann%2Fhivemem-blue)](https://github.com/ufelmann/HiveMem/pkgs/container/hivemem)
[![Java](https://img.shields.io/badge/java-21-blue)](https://openjdk.org)
[![PostgreSQL](https://img.shields.io/badge/postgresql-16-336791)](https://postgresql.org)
[![MCP Tools](https://img.shields.io/badge/MCP%20tools-38-orange)](https://github.com/ufelmann/HiveMem#tool-list-full)
[![License: Sustainable Use](https://img.shields.io/badge/license-Sustainable%20Use-blue)](https://github.com/ufelmann/HiveMem/blob/main/LICENSE)
[![SafeSkill](https://safeskill.dev/api/badge/ufelmann-hivemem)](https://safeskill.dev/scan/ufelmann-hivemem)

**Docker image:** [`ghcr.io/ufelmann/hivemem:main`](https://github.com/ufelmann/HiveMem/pkgs/container/hivemem)

## Vision & Research

HiveMem is built on the premise that well-structured external knowledge systems are not just storage -- they extend cognition. Every design decision is grounded in research on how humans process, retain, and retrieve information.

### Scientific Foundations

| Theory | Key Insight | HiveMem Consequence |
|---|---|---|
| **Working Memory Limitation** (Cowan, 2001) | Humans hold ~4 items in working memory | Wake-up context delivers max 15-20 items, prioritized by importance |
| **Cognitive Load Theory** (Sweller, 1988) | Disorganized information wastes mental resources needed for thinking | Wings/Halls/Rooms taxonomy, Blueprints, progressive summarization |
| **Extended Mind Thesis** (Clark & Chalmers, 1998) | Well-used external tools become genuine extensions of cognition | Proactive capturing, graph traversal for hidden connections, synthesis agents |
| **Forgetting Curve** (Ebbinghaus, 1885) | 90% of learned information is lost within a week | Immediate capture at session end, proactive storage of decisions |

### PKM Frameworks

**Zettelkasten** (Luhmann) -- Atomic notes + linking. Knowledge emerges from connections, not hierarchies. Luhmann produced 70 books and 400 papers from 90,000 linked notes.

*What HiveMem adopts:* Atomic drawers (one topic per drawer), knowledge graph as linking (facts), drawer-to-drawer tunnels with temporal versioning (related_to, builds_on, contradicts, refines).
*What HiveMem does differently:* Semi-automatic linking -- LLM agents create tunnels after archiving based on semantic search. Bidirectional traversal. Temporal validity -- notes and tunnels can expire.

**PARA** (Tiago Forte) -- Projects / Areas / Resources / Archive. Sorted by actionability, not topic.

*What HiveMem adopts:* Actionability field (actionable / reference / someday / archive). Wake-up prioritizes actionable over reference. Wings map to Areas.

### References

- Cowan, N. (2001). *The magical number 4 in short-term memory.* Behavioral and Brain Sciences, 24(1), 87-114.
- Sweller, J. (1988). *Cognitive Load During Problem Solving.* Cognitive Science, 12(2), 257-285.
- Clark, A. & Chalmers, D. (1998). *The Extended Mind.* Analysis, 58(1), 7-19.
- Ebbinghaus, H. (1885). *Uber das Gedachtnis.*
- Ahrens, S. (2017). *How to Take Smart Notes.* CreateSpace.
- Forte, T. (2022). *Building a Second Brain.* Atria Books.

## Transparency & Trust

- **Privacy First:** HiveMem is 100% self-hosted. Your data never leaves your infrastructure.
- **Auditability:** All tool calls and authentication events are logged to `/data/audit.log`.
- **Security:** Built-in RBAC (Role-Based Access Control) ensures that agents can only perform actions you approve.

## Features

- **38 MCP tools** across search, knowledge graph, progressive summarization, agent fleet, references, and admin
- **5-signal ranked search** -- semantic similarity + keyword match + recency + importance + popularity
- **Append-only versioning** -- never lose history, revise with parent_id chains, point-in-time queries
- **Progressive summarization** (L0-L3) -- content, summary, key_points, insight per drawer
- **Temporal knowledge graph** -- facts with valid_from/valid_until, contradiction detection, multi-hop traversal
- **Role-based token auth** -- multiple tokens, 4 roles (admin/writer/reader/agent), per-role tool visibility
- **Agent fleet** with approval workflow -- agents write pending suggestions, only admins approve
- **Blueprints** -- curated narrative overviews per wing, append-only versioned
- **References & reading list** -- track sources, link to drawers, filter by type/status
- **Spring Boot 3.3 + Java 21** -- MCP server with jOOQ, Flyway migrations, Caffeine cache
- **244 tests** with Testcontainers -- unit, integration, HTTP end-to-end, performance, security, concurrency

## Prerequisites

- [Docker](https://docs.docker.com/get-docker/) (v20+)
- An external PostgreSQL database with pgvector extension (e.g. `pgvector/pgvector:pg17`)
- An external embeddings service reachable via HTTP

## Quick Start

### Option A: Pre-built image (recommended)

```bash
docker run -d --name hivemem \
  -p 8421:8421 \
  -e HIVEMEM_JDBC_URL=jdbc:postgresql://postgres:5432/hivemem \
  -e HIVEMEM_DB_USER=hivemem \
  -e HIVEMEM_DB_PASSWORD=secret \
  -e HIVEMEM_EMBEDDING_URL=http://embeddings:8081 \
  --restart unless-stopped \
  ghcr.io/ufelmann/hivemem:main
```

### Option B: Build from source

```bash
git clone https://github.com/ufelmann/HiveMem.git
cd HiveMem
docker build -t hivemem .
docker run -d --name hivemem \
  -p 8421:8421 \
  -e HIVEMEM_JDBC_URL=jdbc:postgresql://postgres:5432/hivemem \
  -e HIVEMEM_DB_USER=hivemem \
  -e HIVEMEM_DB_PASSWORD=secret \
  -e HIVEMEM_EMBEDDING_URL=http://embeddings:8081 \
  --restart unless-stopped \
  hivemem
```

### Option C: Docker Compose

```yaml
services:
  hivemem:
    image: ghcr.io/ufelmann/hivemem:main
    container_name: hivemem
    ports:
      - "8421:8421"
    environment:
      HIVEMEM_JDBC_URL: jdbc:postgresql://postgres:5432/hivemem
      HIVEMEM_DB_USER: hivemem
      HIVEMEM_DB_PASSWORD: secret
      HIVEMEM_EMBEDDING_URL: http://embeddings:8081
    restart: unless-stopped
```

```bash
docker compose up -d
```

At startup, Spring Boot runs Flyway migrations against the configured PostgreSQL database. Check progress:

```bash
docker logs -f hivemem
```

Wait for the Spring Boot startup log and a successful `/mcp` response before proceeding.

### Required Environment Variables

| Variable | Description |
|---|---|
| `HIVEMEM_JDBC_URL` | JDBC connection string (e.g. `jdbc:postgresql://postgres:5432/hivemem`) |
| `HIVEMEM_DB_USER` | PostgreSQL username |
| `HIVEMEM_DB_PASSWORD` | PostgreSQL password |
| `HIVEMEM_EMBEDDING_URL` | URL of the external embeddings service |
| `HIVEMEM_API_TOKEN` | Used by `deploy.sh` for the health-check smoke test |

### Create an API token

Use the `hivemem-token` CLI inside the container:

```bash
docker exec hivemem hivemem-token create my-admin --role admin
```

The plaintext token is printed once and never stored. Save it immediately.

### Connect to Claude Code

**CLI (recommended):**

```bash
claude mcp add --scope user hivemem --transport http http://localhost:8421/mcp \
  --header "Authorization: Bearer YOUR_TOKEN_HERE"
```

Restart Claude Code. The 38 HiveMem tools are now available in every session.

**Manual config** (`~/.claude.json` for user-level, or `.mcp.json` for project-level):

```json
{
  "mcpServers": {
    "hivemem": {
      "type": "http",
      "url": "http://localhost:8421/mcp",
      "headers": {
        "Authorization": "Bearer YOUR_TOKEN_HERE"
      }
    }
  }
}
```

### Connect to Claude Desktop

Add to `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "hivemem": {
      "type": "http",
      "url": "http://localhost:8421/mcp",
      "headers": {
        "Authorization": "Bearer YOUR_TOKEN_HERE"
      }
    }
  }
}
```

### Teach your agent to use HiveMem

The MCP server ships instructions that tell the agent *how* to use the 38 tools (call `wake_up` first, check duplicates before adding, etc.). But the agent won't reliably *remember to archive* unless you tell it to in your own CLAUDE.md.

Add this to your **user-level** CLAUDE.md (`~/.claude/CLAUDE.md`) so it applies to every project:

```markdown
## HiveMem — Persistent Knowledge

You have access to HiveMem via MCP. It is your long-term memory. Use it.

### Session start
- Call `hivemem_wake_up` before your first response. No exceptions.
- If the user asks about past work, decisions, or people: `hivemem_search` first, never guess.

### During work
- After completing a significant action (bug fix, feature, design decision, deployment, investigation):
  archive it immediately. Do not batch, do not wait for session end.
- Archiving means: `check_duplicate` → `add_drawer` (all L0-L3 layers) → extract facts (`check_contradiction` → `kg_add`) → link related drawers (`search` → `add_tunnel` for top 2-3 matches).
- When facts change: `kg_invalidate` the old fact first, then `kg_add` the new one.

### Session end
- Before the session ends, archive anything significant that hasn't been stored yet.
- When the user says "archive", "save", or "persist": archive the full session.

### Classification
- Use existing wings and halls. Call `list_wings`/`list_halls` before inventing new ones.
- Wing = major life area, Hall = broad category, Room = specific topic.
- One drawer per topic. Fill ALL layers: content (L0), summary (L1), key_points (L2), insight (L3).
- Every fact needs `valid_from`. Knowledge without timestamps is useless.

### What to archive
- Decisions and their rationale (the "why", not just the "what")
- Discoveries, surprises, lessons learned
- Infrastructure changes, deployment details
- Bug root causes and fixes
- New patterns, conventions, or processes established

### What NOT to archive
- Routine code changes that are obvious from git history
- Temporary debugging steps
- Information already in the project's CLAUDE.md or README
```

**Why user-level?** Project-level CLAUDE.md files describe the *project*. HiveMem is *your* memory across all projects. A user-level CLAUDE.md ensures every agent, in every repo, knows to persist knowledge — even in repos that have never heard of HiveMem.

**Why is the MCP protocol not enough?** The MCP `instructions` field tells the agent *how* to use the tools correctly (check duplicates, fill all layers, etc.). But it cannot force the agent to *decide* to archive — that decision depends on the conversation context, which only the CLAUDE.md can influence. The MCP protocol is the "API docs"; the CLAUDE.md is the "job description".

## The Building

HiveMem organizes knowledge like a building you walk through. Wings, halls, rooms, and drawers -- a spatial hierarchy everyone understands intuitively. Secret tunnels connect drawers across the entire structure, revealing hidden relationships in your knowledge.

```mermaid
graph TB
    subgraph HM["HiveMem"]
        direction TB

        subgraph Wing1["Wing: Projects"]
            direction TB
            subgraph Hall1["Hall: Software"]
                direction LR
                subgraph Room1A["Room: HiveMem"]
                    D1["Drawer<br/><i>L0: content</i><br/><i>L1: summary</i><br/><i>L2: key points</i><br/><i>L3: insight</i>"]
                    D2["Drawer"]
                end
                subgraph Room1B["Room: Website"]
                    D3["Drawer"]
                end
            end
        end

        subgraph Wing2["Wing: Knowledge"]
            direction TB
            subgraph Hall2["Hall: Tech"]
                direction LR
                subgraph Room2A["Room: AI"]
                    D5["Drawer"]
                    D6["Drawer"]
                end
                subgraph Room2B["Room: Security"]
                    D7["Drawer"]
                end
            end
        end
    end

    D1 <-..->|"builds_on"| D5
    D2 <-..->|"related_to"| D3
    D6 <-..->|"contradicts"| D7

    subgraph KG["Knowledge Graph"]
        F1["Fact<br/><i>subject _ predicate _ object</i><br/><i>valid_from / valid_until</i>"]
    end

    subgraph BP["Blueprint"]
        M1["Narrative overview<br/><i>per wing</i>"]
    end

    D1 -.->|"source"| F1
    Wing1 -.-> M1

    classDef wing fill:#4a90d9,stroke:#2c5f8a,color:white
    classDef hall fill:#5ba85b,stroke:#3d7a3d,color:white
    classDef room fill:#e8a838,stroke:#b8802a,color:white
    classDef drawer fill:#f5f5f5,stroke:#999,color:#333
    classDef kg fill:#c0392b,stroke:#962d22,color:white
    classDef bp fill:#9b59b6,stroke:#7d3c98,color:white
    classDef hm fill:#f0f4f8,stroke:#4a90d9,color:#333

    class Wing1,Wing2 wing
    class Hall1,Hall2 hall
    class Room1A,Room1B,Room2A,Room2B room
    class D1,D2,D3,D5,D6,D7 drawer
    class KG,F1 kg
    class BP,M1 bp
    class HM hm
```

### Concepts

| Concept | Description | Example |
|---|---|---|
| **Wing** | Top-level category -- a wing of the building | "Projects", "Knowledge", "Cooking" |
| **Hall** | A hall within a wing | "Software", "Italian Cuisine" |
| **Room** | A room within a hall | "HiveMem", "Pasta Recipes" |
| **Drawer** | Single knowledge item with 4 layers (L0-L3) | A design decision, a recipe, a meeting note |
| **Tunnel** | Secret passage connecting two drawers | `builds_on`, `related_to`, `contradicts`, `refines` |
| **Fact** | Atomic knowledge triple in the knowledge graph | "HiveMem → uses → PostgreSQL" with temporal validity |
| **Blueprint** | Narrative overview of a wing | How halls, rooms, and key drawers in a wing connect |

### How it works

1. **Store** -- Content is classified into wing/hall/room and stored as a drawer with progressive summarization (L0: full content, L1: summary, L2: key points, L3: insight)
2. **Connect** -- Tunnels link related drawers across the building; facts capture atomic relationships in the knowledge graph
3. **Search** -- 5-signal ranked search finds drawers by meaning, keywords, recency, importance, and popularity
4. **Traverse** -- Follow tunnels to discover hidden connections; use time machine to see what was known at any point
5. **Wake up** -- Each session starts with identity context and critical facts, like walking back into the building and remembering where everything is

## Architecture

```mermaid
graph TB
    Client["Claude / MCP Client"]

    subgraph Container["Docker Container (eclipse-temurin:21-jre)"]
        Auth["AuthFilter<br/><i>Token auth + role check + rate limit</i>"]
        ToolGate["ToolPermissionService<br/><i>Filter tools/list by role</i>"]
        Identity["Identity Injection<br/><i>created_by from token</i>"]
        MCP["McpController<br/>:8421<br/><i>38 tools, Streamable HTTP</i>"]
    end

    EmbSvc["External Embeddings Service<br/><i>HTTP API</i>"]
    PG["External PostgreSQL<br/><i>pgvector, Flyway-managed schema</i>"]

    Client -->|"MCP over HTTP"| Auth
    Auth --> ToolGate
    ToolGate --> Identity
    Identity --> MCP
    MCP -->|"HTTP"| EmbSvc
    MCP -->|"JDBC"| PG
```

### Data Model

```mermaid
erDiagram
    drawers {
        UUID id PK
        UUID parent_id FK
        TEXT content
        vector embedding
        TEXT wing
        TEXT hall
        TEXT room
        TEXT summary
        TEXT[] key_points
        TEXT insight
        TEXT actionability
        SMALLINT importance
        TEXT status
        TIMESTAMPTZ valid_from
        TIMESTAMPTZ valid_until
    }
    facts {
        UUID id PK
        UUID parent_id FK
        TEXT subject
        TEXT predicate
        TEXT object
        REAL confidence
        UUID source_id FK
        TEXT status
        TIMESTAMPTZ valid_from
        TIMESTAMPTZ valid_until
    }
    tunnels {
        UUID id PK
        UUID from_drawer FK
        UUID to_drawer FK
        TEXT relation
        TEXT note
        TEXT status
        TEXT created_by
        TIMESTAMPTZ valid_from
        TIMESTAMPTZ valid_until
    }
    blueprints {
        UUID id PK
        TEXT wing
        TEXT title
        TEXT narrative
        TEXT[] hall_order
        UUID[] key_drawers
        TIMESTAMPTZ valid_from
        TIMESTAMPTZ valid_until
    }
    api_tokens {
        UUID id PK
        TEXT token_hash
        TEXT name
        TEXT role
        TIMESTAMPTZ expires_at
        TIMESTAMPTZ revoked_at
    }
    agents {
        TEXT name PK
        TEXT focus
        JSONB autonomy
        TEXT schedule
    }
    references_ {
        UUID id PK
        TEXT title
        TEXT url
        TEXT ref_type
        TEXT status
        SMALLINT importance
    }

    drawers ||--o{ facts : "source_id"
    drawers ||--o{ drawers : "parent_id (revision chain)"
    facts ||--o{ facts : "parent_id (revision chain)"
    drawers ||--o{ drawer_references : "links"
    references_ ||--o{ drawer_references : "links"
    agents ||--o{ agent_diary : "writes"
    drawers ||--o{ access_log : "tracked"
```

### Security & Capability Matrix

Every HiveMem tool is mapped to a specific role to ensure least privilege. Write operations (excluding agents) and admin functions are protected by RBAC.

| Category | Tools | Access Role | Data Flow | HITL Required? | Description |
|---|---|---|---|---|---|
| **Search** | `search`, `search_kg`, `quick_facts`, `time_machine` | `reader` | Read Only | No | 5-signal semantic & keyword search. |
| **Read** | `status`, `get_drawer`, `list_wings`, `list_halls`, `traverse`, `wake_up`, `get_blueprint` | `reader` | Read Only | No | Navigation and context retrieval. |
| **Write** | `add_drawer`, `kg_add`, `kg_invalidate`, `revise_drawer`, `revise_fact`, `update_identity`, `update_blueprint` | `agent` | Propose Change | Yes (for Agents) | Append-only knowledge capture. |
| **Tunnels** | `add_tunnel`, `remove_tunnel` | `agent` | Link Discovery | Yes | Drawer-to-drawer semantic linking. |
| **Integrity** | `check_duplicate`, `check_contradiction`, `approve_pending` | `admin` | Commit Change | Yes | Verification and commit workflow. |
| **Agent** | `register_agent`, `list_agents`, `diary_write`, `diary_read` | `admin` | Fleet Management | Yes | Autonomous fleet orchestration. |
| **References** | `add_reference`, `link_reference`, `reading_list` | `agent` | Metadata | No | Source and citation tracking. |
| **Admin** | `health`, `log_access`, `refresh_popularity` | `admin` | System Management | Yes | Audit and performance monitoring. |

### Configuration

| Variable | Default | Description |
|---|---|---|
| `HIVEMEM_JDBC_URL` | (required) | JDBC connection string to PostgreSQL |
| `HIVEMEM_DB_USER` | (required) | PostgreSQL username |
| `HIVEMEM_DB_PASSWORD` | (required) | PostgreSQL password |
| `HIVEMEM_EMBEDDING_URL` | `http://localhost:8081` | URL of the external embeddings service |
| `HIVEMEM_EMBEDDING_TIMEOUT` | `PT5S` | HTTP timeout for embedding requests (ISO 8601 duration) |
| `SERVER_PORT` | `8421` | Port for the MCP server |

### Security & Compliance

- **SafeSkill Score:** **100/100 (Verified Safe)**. See [SafeSkill Report](https://safeskill.dev/scan/ufelmann-hivemem).
- **Transparency:** 7/7 points. See [SAFE.md](SAFE.md) for the security manifest.
- **Audit Logging:** Every tool call is logged in JSON to `/data/audit.log`.
- **Human-in-the-Loop:** All agent writes require manual approval via `hivemem_approve_pending`.

### Tool List (Full)

1. `hivemem_search`: Semantic similarity + keyword search.
2. `hivemem_search_kg`: Knowledge graph triple lookup.
3. `hivemem_quick_facts`: Context-aware facts about an entity.
4. `hivemem_time_machine`: Historical knowledge retrieval.
5. `hivemem_status`: System overview and counts.
6. `hivemem_get_drawer`: Read single knowledge item.
7. `hivemem_list_wings`: List top-level categories.
8. `hivemem_list_halls`: List mid-level categories.
9. `hivemem_traverse`: Recursive graph traversal.
10. `hivemem_wake_up`: Initial session context.
11. `hivemem_get_blueprint`: Narrative wing overviews.
12. `hivemem_drawer_history`: Trace revisions of a drawer.
13. `hivemem_fact_history`: Trace revisions of a fact.
14. `hivemem_pending_approvals`: List work awaiting review.
15. `hivemem_reading_list`: Manage unread/in-progress sources.
16. `hivemem_list_agents`: View active agent fleet.
17. `hivemem_diary_read`: Admin tool to read agent diaries.
18. `hivemem_add_drawer`: Store L0-L3 knowledge layers.
19. `hivemem_kg_add`: Create a new fact triple.
20. `hivemem_kg_invalidate`: Soft-delete/expire a fact.
21. `hivemem_revise_drawer`: Create a new version of a drawer.
22. `hivemem_revise_fact`: Create a new version of a fact.
23. `hivemem_update_identity`: Update session context facts.
24. `hivemem_update_blueprint`: Update wing narrative.
25. `hivemem_add_tunnel`: Link two drawers together.
26. `hivemem_remove_tunnel`: Expire a drawer link.
27. `hivemem_check_duplicate`: Verify knowledge doesn't exist.
28. `hivemem_check_contradiction`: Detect logic conflicts in KG.
29. `hivemem_add_reference`: Store source documents/URLs.
30. `hivemem_link_reference`: Cite source for a drawer.
31. `hivemem_register_agent`: Add an agent to the fleet.
32. `hivemem_diary_write`: Agent-private reflection tool.
33. `hivemem_mine_file`: Import a file as a drawer.
34. `hivemem_mine_directory`: Batch import a directory.
35. `hivemem_approve_pending`: Admin tool to commit agent work.
36. `hivemem_health`: Monitor DB and service state.
37. `hivemem_log_access`: Popularity signal ingestion.
38. `hivemem_refresh_popularity`: Update search signal cache.

### Search Signals

The `hivemem_search` tool combines 5 signals with configurable weights:

| Signal | Default Weight | Description |
|---|---|---|
| Semantic | 0.35 | Vector cosine similarity |
| Keyword | 0.15 | PostgreSQL full-text search (tsvector, BM25-like) |
| Recency | 0.20 | Exponential decay, 90-day half-life |
| Importance | 0.15 | User/agent assigned 1-5 scale |
| Popularity | 0.15 | Access frequency (materialized view) |

### Progressive Summarization

Every drawer supports 4 layers of progressive summarization:

| Layer | Field | Purpose |
|---|---|---|
| L0 | `content` | Full verbatim text |
| L1 | `summary` | One-sentence summary for scanning |
| L2 | `key_points` | 3-5 core takeaways |
| L3 | `insight` | Personal conclusion / implication |

Plus `actionability` (actionable / reference / someday / archive) and `importance` (1-5).

## Authentication & Authorization

Tokens are stored as SHA-256 hashes in PostgreSQL. The plaintext is shown exactly once at creation and never stored. Auth responses are cached with Caffeine (60s TTL, max 1000 entries).

### Roles

Each token has one of four roles. The role controls which tools the client sees in `tools/list` and which it can call.

| Role | Visible tools | Write behavior | Can approve? |
|---|---|---|---|
| `admin` | All 38 | `status: committed` | Yes |
| `writer` | 34 (no admin tools) | `status: committed` | No |
| `reader` | 17 (read only) | Can't write | No |
| `agent` | 34 (same as writer) | `status: pending` | No |

The `agent` role is the key constraint: agents can add knowledge, but every write goes into a pending queue. Only an admin can approve or reject it. This prevents any agent from writing and self-approving in the same session.

`created_by` is set automatically from the token name. Clients can't override it.

### Token management

```bash
docker exec hivemem hivemem-token create <name> --role admin|writer|reader|agent [--expires 90d]
docker exec hivemem hivemem-token list
docker exec hivemem hivemem-token revoke <name>
docker exec hivemem hivemem-token info <name>
```

The `hivemem-token` CLI is a bash script that talks to PostgreSQL directly via `psql`.

### Security details

- **Rate limiting** -- 5 failed auth attempts per IP triggers a 15-minute ban
- **Audit log** -- every request logged to `/data/audit.log`
- **Timing-safe** -- token comparison uses SHA-256 hash lookup, not string comparison
- **Path traversal protection** -- file import restricted to `/data/imports` and `/tmp`
- **Tool call enforcement** -- `tools/call` checked against role permissions, not just `tools/list` filtering

## Backups

`hivemem-backup` is a bash script that runs `pg_dump | gzip` using `HIVEMEM_DB_PASSWORD` / `HIVEMEM_DB_USER` / `HIVEMEM_DB_NAME` / `HIVEMEM_DB_HOST` environment variables. The last 7 daily dumps are kept in `/data/backups/`.

Manual backup:

```bash
docker exec hivemem hivemem-backup
```

**LXC/Proxmox users:** Schedule a vzdump at 02:00 to capture the full container including the database dumps. This gives you both logical (pg_dump) and physical (filesystem) backup coverage.

## Development

### Run tests (no deployment needed)

Tests use [Testcontainers](https://java.testcontainers.org/) -- a `pgvector/pgvector:pg17` container is started and destroyed per session. Embeddings are stubbed with a fixed test client (deterministic vectors, no external service needed).

```bash
cd java-server
mvn test
```

```
244 tests passed
```

### Deploy changes

```bash
# Set required env vars first:
export HIVEMEM_JDBC_URL=jdbc:postgresql://postgres:5432/hivemem
export HIVEMEM_DB_USER=hivemem
export HIVEMEM_DB_PASSWORD=secret
export HIVEMEM_EMBEDDING_URL=http://embeddings:8081
export HIVEMEM_API_TOKEN=your-admin-token

./deploy.sh java
```

The script builds the Docker image, restarts the container, and waits for a successful health check on `/mcp`.

### Migrations

Schema changes are managed by [Flyway](https://flywaydb.org/). Migrations run automatically at Spring Boot application startup.

Migration files live in `java-server/src/main/resources/db/migration/` using the Flyway naming convention (`V0001__description.sql`, `V0002__description.sql`, etc.).

To add a new migration:

```bash
cat > java-server/src/main/resources/db/migration/V0008__my_feature.sql << 'EOF'
CREATE TABLE IF NOT EXISTS my_table (...);
EOF
```

Deploy the application -- Flyway applies pending migrations on startup.

### Debugging

```bash
docker logs hivemem --tail 50           # Container logs
docker exec hivemem hivemem-token list  # Show all tokens
```

## License

HiveMem is fair-code licensed under the [Sustainable Use License](LICENSE).

- **Free** for personal use and internal business use
- **Source available** -- inspect, modify, learn
- **Commercially restricted** -- you can't sell HiveMem as a service

See [LICENSING.md](LICENSING.md) for plain-English details and examples.
