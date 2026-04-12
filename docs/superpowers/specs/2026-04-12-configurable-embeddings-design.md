# Design Spec: Configurable Embeddings & Automatic Recomputation

**Status:** Draft
**Date:** 2026-04-12
**Author:** Gemini CLI
**Topic:** Infrastructure / Machine Learning / Database

## 1. Problem Statement
Currently, HiveMem is hardcoded to use the `BAAI/bge-m3` embedding model with a fixed dimension of 1024. Changing the model requires manual database migrations and code changes. There is no automated way to recompute existing embeddings when switching models, especially when the vector dimension changes.

## 2. Goals
- Make the embedding model configurable via environment variables.
- Support multiple embedding engines (initially `FlagEmbedding` and `sentence-transformers`).
- Automatically detect model changes on server startup.
- Perform a safe, automated recomputation of all embeddings if the model or dimension changes.
- Ensure data safety through mandatory backups before destructive operations.

## 3. Proposed Changes

### 3.1 Configuration (`hivemem/embeddings.py`)
- Introduce `HIVEMEM_EMBEDDING_MODEL` env var (Default: `sentence-transformers/paraphrase-multilingual-MiniLM-L6-v2`).
- Introduce `HIVEMEM_EMBEDDING_ENGINE` env var (Automatic detection based on model name, or explicit override).
- Add `get_dimension()` to dynamically determine the vector size by running a test encoding.
- Update `encode()` to use the configured engine and model.

### 3.2 State Tracking (`identity` table)
- Use the existing `identity` table to store metadata about the current database state:
    - `embedding_model`: The name of the model used for the current `embedding` column.
    - `embedding_dimension`: The dimension of the current `embedding` column.

### 3.3 Recomputation Manager (`hivemem/recompute_embeddings.py`)
- **Detection Logic:** Compare configured model/dimension with values in `identity`.
- **Pre-Migration Backup:** Invoke `scripts/hivemem-backup` logic to create a full DB dump in `/data/backups/pre_migration_<timestamp>.sql.gz`.
- **Schema Migration (SQL):**
    - `DROP INDEX IF EXISTS idx_drawers_embedding;`
    - `ALTER TABLE drawers DROP COLUMN IF EXISTS embedding;`
    - `ALTER TABLE drawers ADD COLUMN embedding vector(<NEW_DIMENSION>);`
- **Batch Processing:**
    - Fetch all `drawers` with `content`.
    - Compute new embeddings in batches (e.g., size 100).
    - Update the `embedding` column for each row.
- **Post-Migration:**
    - Recreate the HNSW index: `CREATE INDEX idx_drawers_embedding ON drawers USING hnsw (embedding vector_cosine_ops);`.
    - Update `identity` table with new model/dimension metadata.
- **Concurrency Control:** Use `pg_advisory_lock` to prevent parallel migration attempts.

### 3.4 Server Integration (`hivemem/server.py`)
- Integrate the check into the `lifespan` startup event of the FastAPI/FastMCP app.
- Ensure the database pool is available before running the check.

## 4. Error Handling & Safety
- **Advisory Locks:** Prevent multiple instances from migrating the same database.
- **Mandatory Backup:** Never drop the embedding column without a successful backup first.
- **Transactionality:** While `DROP COLUMN` is transactional in Postgres, the batch updates are not one large transaction to avoid long-held locks and WAL bloat. Progress is tracked row-by-row or batch-by-batch if possible (though for Approach 1, a clean start is preferred).

## 5. Dependencies
- Add `sentence-transformers` to `pyproject.toml`.

## 6. Testing Strategy
- **Unit Test:** Verify `get_dimension()` and `encode()` with different models.
- **Integration Test:** Mock a model change and verify that:
    1. A backup is created.
    2. The schema is updated correctly.
    3. All embeddings are recomputed and match the new model's output.
    4. Metadata in `identity` is updated.
