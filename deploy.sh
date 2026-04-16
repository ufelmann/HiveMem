#!/bin/bash
set -euo pipefail

CONTAINER_NAME="hivemem"
IMAGE_NAME="hivemem"
MODE="${1:-java}"

if [ "$MODE" != "java" ]; then
    echo "Only the Java runtime is supported on this branch." >&2
    exit 1
fi

if [ -z "${HIVEMEM_JDBC_URL:-}" ] || [ -z "${HIVEMEM_DB_USER:-}" ] || [ -z "${HIVEMEM_DB_PASSWORD:-}" ] || [ -z "${HIVEMEM_EMBEDDING_URL:-}" ] || [ -z "${HIVEMEM_API_TOKEN:-}" ]; then
    echo "HIVEMEM_JDBC_URL, HIVEMEM_DB_USER, HIVEMEM_DB_PASSWORD, HIVEMEM_EMBEDDING_URL and HIVEMEM_API_TOKEN must be set." >&2
    exit 1
fi

echo "Building jar with JDK 25..."
docker run --rm --security-opt apparmor=unconfined \
    -v "$(pwd)":/workspace -w /workspace/java-server \
    -v "$HOME/.m2":/root/.m2 \
    maven:3.9.13-eclipse-temurin-25 \
    mvn -q -DskipTests package

echo "Building app image..."
cp java-server/target/app.jar /tmp/hivemem-app.jar
cp entrypoint.sh /tmp/hivemem-entrypoint.sh
cp scripts/hivemem-migrate /tmp/hivemem-migrate
docker build -f - -t "$IMAGE_NAME:latest" /tmp <<'DOCKERFILE'
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY hivemem-app.jar /app/app.jar
COPY hivemem-entrypoint.sh /app/entrypoint.sh
COPY hivemem-migrate /usr/local/bin/hivemem-migrate
RUN chmod +x /app/entrypoint.sh /usr/local/bin/hivemem-migrate
EXPOSE 8421
ENTRYPOINT ["/app/entrypoint.sh"]
DOCKERFILE

# Restart container
echo "Restarting container..."
docker stop "$CONTAINER_NAME" 2>/dev/null || true
docker rm "$CONTAINER_NAME" 2>/dev/null || true
docker run -d --name "$CONTAINER_NAME" \
    -p 8421:8421 \
    -e HIVEMEM_JDBC_URL="$HIVEMEM_JDBC_URL" \
    -e HIVEMEM_DB_USER="$HIVEMEM_DB_USER" \
    -e HIVEMEM_DB_PASSWORD="$HIVEMEM_DB_PASSWORD" \
    -e HIVEMEM_EMBEDDING_URL="$HIVEMEM_EMBEDDING_URL" \
    --restart unless-stopped \
    "$IMAGE_NAME:latest"

# Wait for health
echo "Waiting for startup..."
for i in $(seq 1 30); do
    if curl -sf http://localhost:8421/mcp \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer ${HIVEMEM_API_TOKEN}" \
        -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' > /dev/null 2>&1; then
        echo "HiveMem Java ready on port 8421."
        exit 0
    fi
    sleep 2
done

echo "WARNING: Health check timed out. Check logs: docker logs $CONTAINER_NAME"
exit 1
