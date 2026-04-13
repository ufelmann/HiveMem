#!/bin/sh
set -eu

if [ -z "${HIVEMEM_JDBC_URL:-}" ]; then
    echo "HIVEMEM_JDBC_URL is required for the Java runtime." >&2
    exit 1
fi

if [ -z "${HIVEMEM_DB_USER:-}" ]; then
    echo "HIVEMEM_DB_USER is required for the Java runtime." >&2
    exit 1
fi

if [ -z "${HIVEMEM_DB_PASSWORD:-}" ]; then
    echo "HIVEMEM_DB_PASSWORD is required for the Java runtime." >&2
    exit 1
fi

if [ -z "${HIVEMEM_EMBEDDING_URL:-}" ]; then
    echo "HIVEMEM_EMBEDDING_URL is required for the Java runtime." >&2
    exit 1
fi

echo "Starting HiveMem Java runtime on port ${SERVER_PORT:-8421}..."
exec java ${JAVA_OPTS:-} -jar /app/app.jar "$@"
