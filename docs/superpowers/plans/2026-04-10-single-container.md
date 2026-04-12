# Single-Container Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Consolidate the two-container Docker stack into a single container with PostgreSQL + MCP Server, shell entrypoint, and built-in backup command.

**Architecture:** Single image based on postgres:17-bookworm with Python 3.11 installed via apt. Shell entrypoint starts PostgreSQL, waits for ready, pre-loads BGE-M3 model, then execs the MCP server as PID 1. One volume at `/data` for pgdata, model cache, and backups.

**Tech Stack:** PostgreSQL 17, pgvector 0.8.2, Apache AGE 1.7.0, Python 3.11, FastMCP, psycopg3, BGE-M3, torch (CPU)

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `Dockerfile` | Rewrite | Single multi-stage: build pgvector+AGE, then PG+Python+hivemem |
| `Dockerfile.db` | Delete | Merged into Dockerfile |
| `entrypoint.sh` | Create | PG init/start, wait, pre-load model, exec MCP server |
| `scripts/hivemem-backup` | Create | pg_dump + gzip to /data/backups, 7-day retention |
| `hivemem/server.py` | Modify | Default DB URL → Unix socket |
| `hivemem/db.py` | Modify | max_size 10 → 20 |
| `scripts/seed-identity.py` | Modify | Default DB URL → Unix socket |
| `tests/conftest.py` | Modify | Default DB URL → Unix socket |
| `docker-compose.yml` | Rewrite | Single service, one volume |
| `scripts/backup.sh` | Delete | Replaced by scripts/hivemem-backup |
| `caddy/Caddyfile` | Delete | Already unused |
| `README.md` | Rewrite | docker run, new diagram, implementation procedures |

---

### Task 1: Create entrypoint.sh

**Files:**
- Create: `entrypoint.sh`

- [ ] **Step 1: Write entrypoint script**

```bash
#!/bin/bash
set -euo pipefail

PGDATA="${PGDATA:-/data/pgdata}"
export PGDATA

# Initialize PostgreSQL if data directory is empty
if [ ! -f "$PGDATA/PG_VERSION" ]; then
    echo "Initializing PostgreSQL..."
    initdb -U hivemem -D "$PGDATA" --auth=trust
    echo "unix_socket_directories = '/var/run/postgresql'" >> "$PGDATA/postgresql.conf"
    echo "listen_addresses = 'localhost'" >> "$PGDATA/postgresql.conf"
fi

# Start PostgreSQL in background
echo "Starting PostgreSQL..."
pg_ctl start -D "$PGDATA" -l /var/log/postgresql.log -o "-k /var/run/postgresql"

# Wait for PostgreSQL to be ready
echo "Waiting for PostgreSQL..."
for i in $(seq 1 30); do
    if pg_isready -U hivemem -h /var/run/postgresql -q; then
        break
    fi
    if [ "$i" -eq 30 ]; then
        echo "PostgreSQL failed to start"
        cat /var/log/postgresql.log
        exit 1
    fi
    sleep 1
done

# Create database if it doesn't exist
psql -U hivemem -h /var/run/postgresql -tc "SELECT 1 FROM pg_database WHERE datname = 'hivemem'" | grep -q 1 || \
    psql -U hivemem -h /var/run/postgresql -c "CREATE DATABASE hivemem"

# Apply extensions and schema
psql -U hivemem -h /var/run/postgresql -d hivemem -c "CREATE EXTENSION IF NOT EXISTS vector"
psql -U hivemem -h /var/run/postgresql -d hivemem -c "CREATE EXTENSION IF NOT EXISTS age"
psql -U hivemem -h /var/run/postgresql -d hivemem -f /app/hivemem/schema.sql 2>/dev/null || true

# Create backup directory
mkdir -p /data/backups

# Pre-load BGE-M3 embedding model
echo "Loading BGE-M3 embedding model..."
python3 -c "from hivemem.embeddings import get_model; get_model()"
echo "Model ready."

# Start MCP server (replaces this shell, becomes PID 1)
echo "Starting MCP server on port ${HIVEMEM_PORT:-8421}..."
exec python3 -m hivemem.server
```

- [ ] **Step 2: Make executable**

Run: `chmod +x entrypoint.sh`

- [ ] **Step 3: Commit**

```bash
git add entrypoint.sh
git commit -m "feat: add single-container entrypoint script"
```

---

### Task 2: Create hivemem-backup script

**Files:**
- Create: `scripts/hivemem-backup`

- [ ] **Step 1: Write backup script**

```bash
#!/bin/bash
set -euo pipefail

BACKUP_DIR="/data/backups"
DATE=$(date +%Y-%m-%d)
DUMP_FILE="$BACKUP_DIR/hivemem-$DATE.sql.gz"

mkdir -p "$BACKUP_DIR"

pg_dump -U hivemem -h /var/run/postgresql hivemem | gzip > "$DUMP_FILE"

# Keep only last 7 daily dumps
ls -t "$BACKUP_DIR"/hivemem-*.sql.gz 2>/dev/null | tail -n +8 | xargs -r rm

echo "[$(date)] Backup complete: $DUMP_FILE ($(du -h "$DUMP_FILE" | cut -f1))"
```

- [ ] **Step 2: Make executable**

Run: `chmod +x scripts/hivemem-backup`

- [ ] **Step 3: Commit**

```bash
git add scripts/hivemem-backup
git commit -m "feat: add hivemem-backup CLI command for docker exec"
```

---

### Task 3: Rewrite Dockerfile

**Files:**
- Rewrite: `Dockerfile`
- Delete: `Dockerfile.db`

- [ ] **Step 1: Write new single-stage Dockerfile**

```dockerfile
FROM postgres:17-bookworm AS builder

RUN apt-get update && apt-get install -y \
    build-essential git \
    postgresql-server-dev-17 \
    libreadline-dev zlib1g-dev flex bison \
    && rm -rf /var/lib/apt/lists/*

# Build pgvector
RUN cd /tmp \
    && git clone --branch v0.8.2 https://github.com/pgvector/pgvector.git \
    && cd pgvector \
    && make PG_CONFIG=/usr/lib/postgresql/17/bin/pg_config \
    && make install PG_CONFIG=/usr/lib/postgresql/17/bin/pg_config

# Build Apache AGE (PG17 branch)
RUN cd /tmp \
    && git clone --branch release/PG17/1.7.0 https://github.com/apache/age.git \
    && cd age \
    && make PG_CONFIG=/usr/lib/postgresql/17/bin/pg_config \
    && make install PG_CONFIG=/usr/lib/postgresql/17/bin/pg_config

FROM postgres:17-bookworm

# Copy extensions from builder
COPY --from=builder /usr/lib/postgresql/17/lib/vector.so /usr/lib/postgresql/17/lib/
COPY --from=builder /usr/share/postgresql/17/extension/vector* /usr/share/postgresql/17/extension/
COPY --from=builder /usr/lib/postgresql/17/lib/age.so /usr/lib/postgresql/17/lib/
COPY --from=builder /usr/share/postgresql/17/extension/age* /usr/share/postgresql/17/extension/

# Install Python
RUN apt-get update && apt-get install -y \
    python3 python3-pip python3-venv libpq-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install Python dependencies (cache layer)
COPY pyproject.toml .
COPY hivemem/__init__.py hivemem/
RUN pip install --no-cache-dir --break-system-packages torch --index-url https://download.pytorch.org/whl/cpu
RUN pip install --no-cache-dir --break-system-packages .

# Copy application code
COPY hivemem/ hivemem/
COPY scripts/ scripts/
COPY entrypoint.sh .

# Install backup command
RUN cp scripts/hivemem-backup /usr/local/bin/hivemem-backup \
    && chmod +x /usr/local/bin/hivemem-backup

# Environment defaults
ENV PGDATA=/data/pgdata \
    HF_HOME=/data/models \
    HIVEMEM_PORT=8421 \
    HIVEMEM_DB_URL="postgresql://hivemem@/hivemem?host=/var/run/postgresql"

RUN mkdir -p /var/run/postgresql && chown postgres:postgres /var/run/postgresql \
    && mkdir -p /data && chown postgres:postgres /data

EXPOSE 8421

USER postgres
ENTRYPOINT ["/app/entrypoint.sh"]
```

- [ ] **Step 2: Delete old Dockerfile.db**

Run: `rm Dockerfile.db`

- [ ] **Step 3: Commit**

```bash
git add Dockerfile
git rm Dockerfile.db
git commit -m "feat: single Dockerfile with PG + Python + extensions"
```

---

### Task 4: Update Python code for Unix socket

**Files:**
- Modify: `hivemem/server.py:33-36`
- Modify: `hivemem/db.py:17`
- Modify: `scripts/seed-identity.py:15-17`
- Modify: `tests/conftest.py:8-11`

- [ ] **Step 1: Update server.py default DB URL**

In `hivemem/server.py`, change:

```python
DB_URL = os.environ.get(
    "HIVEMEM_DB_URL",
    "postgresql://hivemem:hivemem_local_only@db:5432/hivemem",
)
```

To:

```python
DB_URL = os.environ.get(
    "HIVEMEM_DB_URL",
    "postgresql://hivemem@/hivemem?host=/var/run/postgresql",
)
```

- [ ] **Step 2: Update db.py pool max_size**

In `hivemem/db.py`, change:

```python
        pool = AsyncConnectionPool(
            db_url,
            min_size=2,
            max_size=10,
```

To:

```python
        pool = AsyncConnectionPool(
            db_url,
            min_size=2,
            max_size=20,
```

- [ ] **Step 3: Update seed-identity.py default DB URL**

In `scripts/seed-identity.py`, change:

```python
DB_URL = os.environ.get(
    "HIVEMEM_DB_URL",
    "postgresql://hivemem:hivemem_local_only@localhost:5432/hivemem",
)
```

To:

```python
DB_URL = os.environ.get(
    "HIVEMEM_DB_URL",
    "postgresql://hivemem@/hivemem?host=/var/run/postgresql",
)
```

- [ ] **Step 4: Update conftest.py default test DB URL**

In `tests/conftest.py`, change:

```python
TEST_DB_URL = os.environ.get(
    "HIVEMEM_TEST_DB_URL",
    "postgresql://hivemem:hivemem_local_only@localhost:5432/hivemem_test",
)
```

To:

```python
TEST_DB_URL = os.environ.get(
    "HIVEMEM_TEST_DB_URL",
    "postgresql://hivemem@/hivemem_test?host=/var/run/postgresql",
)
```

- [ ] **Step 5: Commit**

```bash
git add hivemem/server.py hivemem/db.py scripts/seed-identity.py tests/conftest.py
git commit -m "refactor: switch DB connections to Unix socket, raise pool max_size to 20"
```

---

### Task 5: Rewrite docker-compose.yml

**Files:**
- Rewrite: `docker-compose.yml`

- [ ] **Step 1: Write simplified docker-compose.yml**

```yaml
services:
  hivemem:
    build: .
    ports:
      - "8421:8421"
    volumes:
      - hivemem_data:/data
    restart: unless-stopped

volumes:
  hivemem_data:
```

- [ ] **Step 2: Commit**

```bash
git add docker-compose.yml
git commit -m "refactor: simplify docker-compose to single service"
```

---

### Task 6: Delete obsolete files

**Files:**
- Delete: `scripts/backup.sh`
- Delete: `caddy/Caddyfile`
- Delete: `caddy/` (directory)

- [ ] **Step 1: Remove files**

```bash
git rm scripts/backup.sh
git rm caddy/Caddyfile
rmdir caddy
```

- [ ] **Step 2: Commit**

```bash
git commit -m "chore: remove Caddy and old backup script"
```

---

### Task 7: Build and test locally

**Files:** none (verification only)

- [ ] **Step 1: Build image on CT 102**

```bash
ssh root@192.168.178.137 "pct exec 102 -- bash -c 'cd /root/hivemem && git pull && docker build -t hivemem .'"
```

Expected: Image builds successfully, ~3.5 GB

- [ ] **Step 2: Stop old containers**

```bash
ssh root@192.168.178.137 "pct exec 102 -- bash -c 'docker compose -f /root/hivemem/docker-compose.yml down -v'"
```

- [ ] **Step 3: Run single container**

```bash
ssh root@192.168.178.137 "pct exec 102 -- bash -c 'docker run -d --name hivemem -p 8421:8421 -v hivemem_data:/data --restart unless-stopped hivemem'"
```

- [ ] **Step 4: Wait for startup and check logs**

```bash
ssh root@192.168.178.137 "pct exec 102 -- docker logs -f hivemem"
```

Expected output:
```
Initializing PostgreSQL...
Starting PostgreSQL...
Waiting for PostgreSQL...
Loading BGE-M3 embedding model...
Model ready.
Starting MCP server on port 8421...
INFO:     Uvicorn running on http://0.0.0.0:8421
```

- [ ] **Step 5: Test MCP endpoint**

```bash
ssh root@192.168.178.137 "pct exec 102 -- curl -s http://localhost:8421/mcp -H 'Content-Type: application/json' -d '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"hivemem_health\",\"arguments\":{}}}'"
```

Expected: `db_connected: true`, extensions vector 0.8.2 and age 1.7.0

- [ ] **Step 6: Test backup command**

```bash
ssh root@192.168.178.137 "pct exec 102 -- docker exec hivemem hivemem-backup"
```

Expected: `Backup complete: /data/backups/hivemem-2026-04-10.sql.gz`

- [ ] **Step 7: Test psql access**

```bash
ssh root@192.168.178.137 "pct exec 102 -- docker exec hivemem psql -U hivemem -c 'SELECT count(*) FROM drawers'"
```

Expected: count = 0

---

### Task 8: Update README

**Files:**
- Rewrite: `README.md`

- [ ] **Step 1: Rewrite README with single-container instructions**

Update all sections:
- Quick start: `docker run` instead of `docker compose`
- Prerequisites: same but mention single container
- Installation: simplified (clone, build, run)
- Architecture diagram: remove Caddy, show single container
- Backup: `docker exec hivemem hivemem-backup`
- Keep docker-compose.yml mention as alternative

Full content:

```markdown
# HiveMem

Personal knowledge system with semantic search and temporal knowledge graph.

MCP server backed by PostgreSQL 17 (pgvector + Apache AGE) with BGE-M3 embeddings.

## Features

- 16 MCP tools (search, knowledge graph, time machine, wake-up, import, ...)
- Semantic search with BGE-M3 (1024 dims, 100+ languages, <1s queries)
- Temporal knowledge graph (valid_from/valid_until, historical queries)
- Multi-hop graph traversal (recursive CTEs / Apache AGE)
- Single container deployment (one command: `docker run`)
- Built-in backup command

## Prerequisites

- [Docker](https://docs.docker.com/get-docker/) (v20+)
- ~4 GB free disk space (BGE-M3 model ~2.2 GB + Docker image ~3.5 GB)
- ~3 GB free RAM (BGE-M3 embedding model runs on CPU)

## Installation

### 1. Clone and build

```bash
git clone https://github.com/ufelmann/HiveMem.git
cd HiveMem
docker build -t hivemem .
```

### 2. Run

```bash
docker run -d --name hivemem \
  -p 8421:8421 \
  -v hivemem_data:/data \
  --restart unless-stopped \
  hivemem
```

First start takes a few minutes — the container initializes PostgreSQL and downloads the BGE-M3 embedding model (~2.2 GB). Check progress:

```bash
docker logs -f hivemem
```

Alternatively, use Docker Compose:

```bash
docker compose up -d
```

### 3. Verify

```bash
curl -s http://localhost:8421/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | head -c 200
```

### 4. Connect to Claude

Add to your MCP client config (Claude Desktop `claude_desktop_config.json` or Claude Code `.mcp.json`):

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

All 16 `hivemem_*` tools should now be available.

### 5. Seed identity (optional)

Customize `scripts/seed-identity.py` with your own profile, then:

```bash
docker exec hivemem python3 scripts/seed-identity.py
```

## Backups

```bash
docker exec hivemem hivemem-backup
```

Dumps are saved to `/data/backups/` inside the volume (gzipped, last 7 days kept). For automated daily backups, add a cron job on the host:

```bash
0 3 * * * docker exec hivemem hivemem-backup
```

## Debugging

```bash
# PostgreSQL shell
docker exec -it hivemem psql -U hivemem

# Container logs
docker logs hivemem --tail 50

# Health check
curl -s http://localhost:8421/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hivemem_health","arguments":{}}}'
```

## Architecture

```mermaid
graph TB
    Client["Claude / MCP Client"]

    subgraph Container["Docker Container"]
        MCP["FastMCP Server<br/>:8421<br/><i>Streamable HTTP</i>"]
        BGE["BGE-M3<br/><i>1024d embeddings</i>"]

        subgraph PG["PostgreSQL 17"]
            pgvector["pgvector<br/><i>semantic search</i>"]
            AGE["Apache AGE<br/><i>graph traversal</i>"]
            Tables["drawers · facts · edges · identity"]
        end
    end

    Volume["/data volume<br/><i>pgdata + models + backups</i>"]

    Client -->|"MCP over HTTP"| MCP
    MCP --> BGE
    MCP -->|"Unix socket"| PG
    pgvector --> Tables
    AGE --> Tables
    PG --- Volume
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
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: update README for single-container deployment"
```

---

### Task 9: Push and deploy

- [ ] **Step 1: Push all changes to GitHub**

```bash
git push origin main
```

- [ ] **Step 2: Pull and rebuild on CT 102**

```bash
ssh root@192.168.178.137 "pct exec 102 -- bash -c 'cd /root/hivemem && git pull'"
ssh root@192.168.178.137 "pct exec 102 -- bash -c 'docker stop hivemem && docker rm hivemem || true'"
ssh root@192.168.178.137 "pct exec 102 -- bash -c 'cd /root/hivemem && docker build -t hivemem .'"
ssh root@192.168.178.137 "pct exec 102 -- bash -c 'docker run -d --name hivemem -p 8421:8421 -v hivemem_data:/data --restart unless-stopped hivemem'"
```

- [ ] **Step 3: Verify deployment**

```bash
ssh root@192.168.178.137 "pct exec 102 -- curl -s http://localhost:8421/mcp -H 'Content-Type: application/json' -d '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"hivemem_health\",\"arguments\":{}}}'"
```

Expected: `db_connected: true`, extensions present, server responding

- [ ] **Step 4: Clean up old images**

```bash
ssh root@192.168.178.137 "pct exec 102 -- docker image prune -f"
```
