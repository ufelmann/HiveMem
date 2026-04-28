# HiveMem
<img width="1637" height="811" alt="image" src="https://github.com/user-attachments/assets/b9ceda91-0678-4d9b-bae8-2b5ba69d53d4" />

Personal knowledge system with semantic search, temporal knowledge graph, and progressive summarization. MCP server backed by PostgreSQL (pgvector) with external embeddings. 30 tools, append-only versioning, role-based token auth, agent fleet with approval workflow.

[![CI](https://github.com/ufelmann/HiveMem/actions/workflows/ci.yml/badge.svg)](https://github.com/ufelmann/HiveMem/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/ufelmann/HiveMem/graph/badge.svg)](https://codecov.io/gh/ufelmann/HiveMem)
[![GitHub release](https://img.shields.io/github/v/tag/ufelmann/HiveMem?label=release)](https://github.com/ufelmann/HiveMem/releases)
[![GHCR](https://img.shields.io/badge/ghcr.io-ufelmann%2Fhivemem-blue)](https://github.com/ufelmann/HiveMem/pkgs/container/hivemem)
[![Java](https://img.shields.io/badge/java-25-blue)](https://openjdk.org)
[![Spring Boot](https://img.shields.io/badge/spring%20boot-4.0.5-6DB33F)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/postgresql-17-336791)](https://postgresql.org)
[![Tests](https://img.shields.io/badge/tests-JUnit%20%2B%20Testcontainers-brightgreen)](https://github.com/ufelmann/HiveMem/actions/workflows/ci.yml)
[![MCP Tools](https://img.shields.io/badge/MCP%20tools-30-orange)](documentation/tools.md)
[![License: Sustainable Use](https://img.shields.io/badge/license-Sustainable%20Use-blue)](https://github.com/ufelmann/HiveMem/blob/main/LICENSE)
[![SafeSkill](https://safeskill.dev/api/badge/ufelmann-hivemem)](https://safeskill.dev/scan/ufelmann-hivemem)

**Docker images:** [`ghcr.io/ufelmann/hivemem:main`](https://github.com/ufelmann/HiveMem/pkgs/container/hivemem) for the rolling `main` branch, plus semver tags such as `ghcr.io/ufelmann/hivemem:8.1.0` for cut releases.

## Highlights

- **[6-Signal Ranked Search](documentation/tools.md#search-signals)** — Semantic similarity, keyword, recency, importance, popularity, and graph proximity — combined into one ranked result.
- **[Temporal Knowledge Graph](documentation/architecture.md#data-model)** — Facts with `valid_from`/`valid_until`, contradiction detection, and multi-hop graph traversal.
- **[Progressive Summarization](documentation/tools.md#progressive-summarization)** — Four layers per cell: content, summary, key points, and insight. Never lose nuance.
- **[Append-Only Versioning + Time Machine](documentation/structure.md)** — No data is ever deleted. Query your knowledge at any point in time.
- **[Agent Fleet + Approval Workflow](documentation/auth.md)** — Agents write pending suggestions; only admins approve. Every write is human-gated.
- **[Auto-Inject Hook for Claude Code](documentation/hook/)** — Relevant memories injected into every session automatically, before you even ask.

## Quick Start

No clone needed. Save this as `docker-compose.yml`:

```yaml
services:
  hivemem-db:
    image: pgvector/pgvector:pg17
    container_name: hivemem-db
    environment:
      POSTGRES_DB: hivemem
      POSTGRES_USER: hivemem
      POSTGRES_PASSWORD: ${HIVEMEM_DB_PASSWORD:-changeme}
    volumes:
      - hivemem-pgdata:/var/lib/postgresql/data
    networks:
      - hivemem-net
    restart: unless-stopped

  hivemem-embeddings:
    image: ghcr.io/ufelmann/hivemem-embeddings:latest
    container_name: hivemem-embeddings
    volumes:
      - hivemem-embeddings-models:/app/models
    networks:
      - hivemem-net
    restart: unless-stopped

  hivemem:
    image: ghcr.io/ufelmann/hivemem:latest
    container_name: hivemem
    ports:
      - "8421:8421"
    environment:
      HIVEMEM_JDBC_URL: jdbc:postgresql://hivemem-db:5432/hivemem
      HIVEMEM_DB_USER: hivemem
      HIVEMEM_DB_PASSWORD: ${HIVEMEM_DB_PASSWORD:-changeme}
      HIVEMEM_EMBEDDING_URL: http://hivemem-embeddings:80
    depends_on:
      - hivemem-db
      - hivemem-embeddings
    networks:
      - hivemem-net
    restart: unless-stopped

networks:
  hivemem-net:

volumes:
  hivemem-pgdata:
  hivemem-embeddings-models:
```

```bash
export HIVEMEM_DB_PASSWORD=your-secret-here
docker compose up -d
docker logs -f hivemem
docker exec hivemem hivemem-token create my-admin --role admin
```

→ Full setup guide: [documentation/getting-started.md](documentation/getting-started.md)

## Documentation

| | |
|---|---|
| [Getting Started](documentation/getting-started.md) | Prerequisites, embedding service, token creation, connect to Claude |
| [The Structure](documentation/structure.md) | Realms, signals, topics, cells, tunnels — the knowledge hierarchy |
| [Architecture](documentation/architecture.md) | System diagram, data model, security matrix |
| [Tools](documentation/tools.md) | All 30 MCP tools, search signals, progressive summarization |
| [Authentication](documentation/auth.md) | Roles, token management, security details |
| [Hook Integration](documentation/hook/) | Auto-inject context into Claude Code sessions |
| [Operations](documentation/operations.md) | Backups, deployment, migrations, debugging |
| [Vision](documentation/vision.md) | Scientific foundations, Zettelkasten, PARA |

## License

HiveMem is fair-code licensed under the [Sustainable Use License](LICENSE). Free for personal and internal business use. See [LICENSING.md](LICENSING.md) for details.
