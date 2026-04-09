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
pg_ctl start -D "$PGDATA" -l /data/postgresql.log -o "-k /var/run/postgresql"

# Wait for PostgreSQL to be ready
echo "Waiting for PostgreSQL..."
for i in $(seq 1 30); do
    if pg_isready -h /var/run/postgresql -q; then
        break
    fi
    if [ "$i" -eq 30 ]; then
        echo "PostgreSQL failed to start"
        cat /data/postgresql.log
        exit 1
    fi
    sleep 1
done
echo "PostgreSQL ready."

# Create database if it doesn't exist (connect to default 'postgres' db first)
psql -U hivemem -h /var/run/postgresql -d postgres -tc "SELECT 1 FROM pg_database WHERE datname = 'hivemem'" | grep -q 1 || \
    psql -U hivemem -h /var/run/postgresql -d postgres -c "CREATE DATABASE hivemem"

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
