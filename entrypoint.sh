#!/bin/bash
set -euo pipefail

PGDATA="${PGDATA:-/data/pgdata}"
export PGDATA

# Generate DB password on first start (tokens are managed via CLI)
echo "Checking secrets..."
python3 -c "
from hivemem.security import load_secrets, save_secrets
import secrets
data = load_secrets()
if 'db_password' not in data:
    data['db_password'] = secrets.token_urlsafe(24)
    save_secrets(data)
    print('DB password generated.')
"

# Read DB password from secrets for PG setup
DB_PASSWORD=$(python3 -c "from hivemem.security import load_secrets; print(load_secrets()['db_password'])")

# Initialize PostgreSQL if data directory is empty
if [ ! -f "$PGDATA/PG_VERSION" ]; then
    echo "Initializing PostgreSQL..."
    initdb -U hivemem -D "$PGDATA" --auth=scram-sha-256 --pwfile=<(echo "$DB_PASSWORD")
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

# Set PGPASSWORD for psql commands
export PGPASSWORD="$DB_PASSWORD"

# Create database if it doesn't exist (connect to default 'postgres' db first)
psql -U hivemem -h /var/run/postgresql -d postgres -tc "SELECT 1 FROM pg_database WHERE datname = 'hivemem'" | grep -q 1 || \
    psql -U hivemem -h /var/run/postgresql -d postgres -c "CREATE DATABASE hivemem"

# Apply extensions and schema
psql -U hivemem -h /var/run/postgresql -d hivemem -c "CREATE EXTENSION IF NOT EXISTS vector"
psql -U hivemem -h /var/run/postgresql -d hivemem -c "CREATE EXTENSION IF NOT EXISTS age"
# Apply database migrations
echo "Applying migrations..."
python3 /app/scripts/hivemem-migrate

# Check if any API tokens exist
TOKEN_COUNT=$(psql -U hivemem -h /var/run/postgresql -d hivemem -tAc "SELECT count(*) FROM api_tokens" 2>/dev/null || echo "0")
if [ "$TOKEN_COUNT" = "0" ]; then
    echo ""
    echo "================================================================"
    echo "  WARNING: No API tokens configured."
    echo "  Create one: docker exec hivemem hivemem-token create admin --role admin"
    echo "================================================================"
    echo ""
fi

# Create backup directory
mkdir -p /data/backups

# Set HF offline mode if model is already cached (skip network metadata checks)
if [ -d "$HF_HOME/hub/models--BAAI--bge-m3" ]; then
    export HF_HUB_OFFLINE=1
    echo "BGE-M3 model cached, will load on first request."
else
    echo "BGE-M3 model not cached, will download on first request."
fi

# Start MCP server (replaces this shell, becomes PID 1)
echo "Starting MCP server on port ${HIVEMEM_PORT:-8421}..."
exec python3 -m hivemem.server
