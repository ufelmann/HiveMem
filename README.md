# HiveMem

Personal knowledge system with semantic search and temporal knowledge graph.

MCP server backed by PostgreSQL 17 (pgvector + Apache AGE) with BGE-M3 embeddings.

## Features

- 16 MCP tools (search, knowledge graph, time machine, wake-up, import, ...)
- Semantic search with BGE-M3 (1024 dims, 100+ languages, <1s queries)
- Temporal knowledge graph (valid_from/valid_until, historical queries)
- Multi-hop graph traversal (recursive CTEs / Apache AGE)
- Docker Compose deployment (one command: `docker compose up`)
- Daily pg_dump backups

## Quick Start

```bash
git clone https://github.com/ufelmann/HiveMem.git
cd HiveMem
cp .env.example .env
docker compose up -d
# First start downloads BGE-M3 model (~2.2GB)
```

## MCP Integration

```json
{
  "mcpServers": {
    "hivemem": {
      "type": "http",
      "url": "http://localhost:8421/mcp"
    }
  }
}
```

## Architecture

```mermaid
graph TB
    Client["🧠 Claude / MCP Client"]

    subgraph Docker Compose
        Caddy["Caddy<br/>:8421 → :8420<br/><i>reverse proxy</i>"]
        MCP["FastMCP Server<br/>:8420<br/><i>Streamable HTTP</i>"]
        BGE["BGE-M3<br/><i>1024d embeddings</i>"]

        subgraph PostgreSQL 17
            pgvector["pgvector<br/><i>semantic search</i>"]
            AGE["Apache AGE<br/><i>graph traversal</i>"]
            Tables["drawers · facts · edges · identity"]
        end
    end

    Client -->|"MCP over HTTP"| Caddy
    Caddy --> MCP
    MCP --> BGE
    MCP --> pgvector
    MCP --> AGE
    pgvector --> Tables
    AGE --> Tables
```

### Tools (16)

| Category | Tools |
|---|---|
| **Read** (9) | `search` · `search_kg` · `get_drawer` · `list_wings` · `list_rooms` · `traverse` · `time_machine` · `wake_up` · `status` |
| **Write** (4) | `add_drawer` · `kg_add` · `kg_invalidate` · `update_identity` |
| **Import** (2) | `mine_file` · `mine_directory` |
| **Admin** (1) | `health` |

## License

MIT
