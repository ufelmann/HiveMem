# Operations

## Backups

For full instance portability (Postgres + SeaweedFS attachments + manifest in one tar.gz, with `--mode=move` and `--mode=clone` restore), use the dedicated backup CLI documented in [Backup + Portability](backup.md). Quick form:

```bash
java -jar app.jar --spring.profiles.active=backup \
    backup export --out /var/lib/hivemem/exports/backup.tar.gz
```

For a quick Postgres-only safety net (no attachments, no manifest), the raw `pg_dump` route is still available — but `backup.md` is the canonical mechanism for disaster recovery and host migration:

```bash
# Postgres-only quick dump (no attachments)
docker exec hivemem-db pg_dump -U hivemem hivemem | gzip > "hivemem-$(date +%Y%m%d).sql.gz"
```

The Postgres-only dump alone is **not** sufficient if attachments are enabled — the `attachments` table references SeaweedFS objects by S3 key.

## Run Tests

Tests use [Testcontainers](https://java.testcontainers.org/) — a `pgvector/pgvector:pg17` container is started and destroyed per session. Embeddings are stubbed with a fixed test client (deterministic vectors, no external service needed).

```bash
cd java-server
mvn test
```

The exact test count changes over time; use the CI badge and workflow runs as the current source of truth.

## Deploy Changes

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

## Migrations

Schema changes are managed by [Flyway](https://flywaydb.org/). Migrations run automatically at Spring Boot application startup.

Migration files live in `java-server/src/main/resources/db/migration/` using the Flyway naming convention (`V0001__description.sql`, `V0002__description.sql`, etc.).

To add a new migration:

```bash
cat > java-server/src/main/resources/db/migration/V0009__my_feature.sql << 'EOF'
CREATE TABLE IF NOT EXISTS my_table (...);
EOF
```

Deploy the application — Flyway applies pending migrations on startup.

## Attachment Storage (SeaweedFS)

Attachment storage is optional. Set `HIVEMEM_ATTACHMENT_ENABLED=true` to enable.

### Environment variables

| Variable | Default | Description |
|---|---|---|
| `HIVEMEM_ATTACHMENT_ENABLED` | `false` | Enable attachment storage |
| `SEAWEEDFS_S3_ENDPOINT` | `http://localhost:8333` | SeaweedFS S3 API endpoint |
| `SEAWEEDFS_S3_BUCKET` | `hivemem-attachments` | S3 bucket name |
| `SEAWEEDFS_S3_ACCESS_KEY` | `hivemem` | S3 access key |
| `SEAWEEDFS_S3_SECRET_KEY` | `hivemem_secret` | S3 secret key |

### Backup

The `seaweedfs_data` Docker volume must be backed up together with the PostgreSQL dump. The `attachments` table references objects by their S3 keys — a DB backup without the volume (or vice versa) results in broken references.

### Deployment

SeaweedFS is included in `docker-compose.yml` as a sidecar service. No additional configuration needed for the default setup.

## Debugging

```bash
docker logs hivemem --tail 50  # Container logs
```
