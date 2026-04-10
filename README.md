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
git clone <repo>
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

- **PostgreSQL 17** + pgvector (embeddings) + Apache AGE (graph)
- **FastMCP** server with Streamable HTTP
- **BGE-M3** multilingual embeddings (dense + sparse)
- **Caddy** reverse proxy
- **Docker Compose** orchestration

## License

MIT
