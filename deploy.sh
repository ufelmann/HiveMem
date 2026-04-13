#!/bin/bash
set -euo pipefail

MODE="${1:-python}"
HASH_FILE=".base-hash"
CONTAINER_NAME="hivemem"
IMAGE_NAME="hivemem"
BASE_IMAGE="hivemem-base"

if [ "$MODE" = "java" ]; then
    JAVA_CONTAINER_NAME="hivemem-java"
    JAVA_IMAGE_NAME="hivemem-java"

    echo "Building Java app image..."
    docker build -f java-server/Dockerfile -t "$JAVA_IMAGE_NAME:latest" java-server

    echo "Restarting Java container..."
    docker stop "$JAVA_CONTAINER_NAME" 2>/dev/null || true
    docker rm "$JAVA_CONTAINER_NAME" 2>/dev/null || true
    docker run -d --name "$JAVA_CONTAINER_NAME" \
        -p 8422:8421 \
        -e HIVEMEM_JDBC_URL="${HIVEMEM_JDBC_URL:-jdbc:postgresql://host.docker.internal:5432/hivemem}" \
        -e HIVEMEM_DB_USER="${HIVEMEM_DB_USER:-hivemem}" \
        -e HIVEMEM_DB_PASSWORD="${HIVEMEM_DB_PASSWORD:-hivemem}" \
        -e HIVEMEM_EMBEDDING_URL="${HIVEMEM_EMBEDDING_URL:-http://host.docker.internal:8081}" \
        --restart unless-stopped \
        "$JAVA_IMAGE_NAME:latest"

    echo "Waiting for Java service startup..."
    for i in $(seq 1 30); do
        if curl -sf http://localhost:8422/mcp \
            -H "Content-Type: application/json" \
            -H "Authorization: Bearer ${HIVEMEM_API_TOKEN:-reader-token}" \
            -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' > /dev/null 2>&1; then
            echo "HiveMem Java ready on port 8422."
            exit 0
        fi
        sleep 2
    done

    echo "WARNING: Java health check timed out. Check logs: docker logs $JAVA_CONTAINER_NAME"
    exit 1
fi

# Compute hash of base dependencies
current_hash=$(cat Dockerfile.base pyproject.toml | sha256sum | cut -d' ' -f1)

# Check if base image needs rebuild
rebuild_base=false
if ! docker image inspect "$BASE_IMAGE:latest" > /dev/null 2>&1; then
    echo "Base image not found, building..."
    rebuild_base=true
elif [ ! -f "$HASH_FILE" ] || [ "$(cat "$HASH_FILE")" != "$current_hash" ]; then
    echo "Dependencies changed, rebuilding base image..."
    rebuild_base=true
fi

if [ "$rebuild_base" = true ]; then
    docker build -f Dockerfile.base -t "$BASE_IMAGE:latest" .
    echo "$current_hash" > "$HASH_FILE"
    echo "Base image built."
else
    echo "Base image up to date."
fi

# Build app image (fast, ~5s)
echo "Building app image..."
docker build -t "$IMAGE_NAME:latest" .

# Restart container
echo "Restarting container..."
docker stop "$CONTAINER_NAME" 2>/dev/null || true
docker rm "$CONTAINER_NAME" 2>/dev/null || true
docker run -d --name "$CONTAINER_NAME" \
    -p 8421:8421 \
    -v hivemem_data:/data \
    -v hivemem_models:/data/models \
    --restart unless-stopped \
    "$IMAGE_NAME:latest"

# Wait for health (read token from secrets for auth)
echo "Waiting for startup..."
for i in $(seq 1 90); do
    TOKEN=$(docker exec "$CONTAINER_NAME" hivemem-token 2>/dev/null) && break
    sleep 2
done

if [ -z "${TOKEN:-}" ]; then
    echo "WARNING: Could not read API token. Check logs: docker logs $CONTAINER_NAME"
    exit 1
fi

for i in $(seq 1 30); do
    if curl -sf http://localhost:8421/mcp \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
        -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' > /dev/null 2>&1; then
        echo "HiveMem ready on port 8421."
        exit 0
    fi
    sleep 2
done

echo "WARNING: Health check timed out. Check logs: docker logs $CONTAINER_NAME"
exit 1
