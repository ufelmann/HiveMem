import logging
import subprocess
import os
from typing import Any
from psycopg_pool import AsyncConnectionPool
from hivemem.embeddings import MODEL_NAME, get_dimension, encode
from hivemem.db import fetch_one

logger = logging.getLogger(__name__)

RANKED_SEARCH_SQL = """
DROP FUNCTION IF EXISTS ranked_search(vector, text, text, text, text, integer, real, real, real, real, real);
CREATE OR REPLACE FUNCTION ranked_search(
    query_embedding vector({dim}),
    query_text TEXT,
    p_wing TEXT DEFAULT NULL,
    p_hall TEXT DEFAULT NULL,
    p_room TEXT DEFAULT NULL,
    p_limit INTEGER DEFAULT 10,
    p_weight_semantic REAL DEFAULT 0.35,
    p_weight_keyword REAL DEFAULT 0.15,
    p_weight_recency REAL DEFAULT 0.20,
    p_weight_importance REAL DEFAULT 0.15,
    p_weight_popularity REAL DEFAULT 0.15
)
RETURNS TABLE (
    id UUID,
    content TEXT,
    summary TEXT,
    wing TEXT,
    hall TEXT,
    room TEXT,
    tags TEXT[],
    importance SMALLINT,
    created_at TIMESTAMPTZ,
    valid_from TIMESTAMPTZ,
    score_semantic REAL,
    score_keyword REAL,
    score_recency REAL,
    score_importance REAL,
    score_popularity REAL,
    score_total REAL
) AS $$
BEGIN
    RETURN QUERY
    WITH max_pop AS (
        SELECT GREATEST(MAX(recent_access_count), 1)::REAL AS val FROM drawer_popularity
    ),
    scored AS (
        SELECT
            d.id, d.content, d.summary, d.wing, d.hall, d.room,
            d.tags, d.importance, d.created_at, d.valid_from,

            -- 1. Semantic similarity (0-1)
            CASE WHEN d.embedding IS NOT NULL
                 THEN (1 - (d.embedding <=> query_embedding))::REAL
                 ELSE 0::REAL END
            AS sem,

            -- 2. Keyword match (0-1, BM25-like)
            CASE WHEN query_text IS NOT NULL AND query_text != ''
                 THEN LEAST(ts_rank_cd(d.tsv, plainto_tsquery('english', query_text))::REAL, 1.0::REAL)
                 ELSE 0::REAL END
            AS kw,

            -- 3. Recency (0-1, exponential decay, half-life 90 days)
            EXP(-0.693 * EXTRACT(EPOCH FROM (now() - d.created_at)) / (90 * 86400))::REAL
            AS rec,

            -- 4. Importance (0-1, 1=critical scores highest)
            (CASE d.importance
                WHEN 1 THEN 1.0 WHEN 2 THEN 0.8 WHEN 3 THEN 0.6
                WHEN 4 THEN 0.4 WHEN 5 THEN 0.2 ELSE 0.6 END)::REAL
            AS imp,

            -- 5. Popularity (0-1, normalized by max, computed once)
            COALESCE(dp.recent_access_count::REAL / (SELECT val FROM max_pop), 0)::REAL
            AS pop

        FROM drawers d
        LEFT JOIN drawer_popularity dp ON dp.drawer_id = d.id
        WHERE (d.valid_until IS NULL OR d.valid_until > now())
          AND d.status = 'committed'
          AND (p_wing IS NULL OR d.wing = p_wing)
          AND (p_hall IS NULL OR d.hall = p_hall)
          AND (p_room IS NULL OR d.room = p_room)
    )
    SELECT
        s.id, s.content, s.summary, s.wing, s.hall, s.room,
        s.tags, s.importance, s.created_at, s.valid_from,
        s.sem, s.kw, s.rec, s.imp, s.pop,
        (s.sem * p_weight_semantic +
         s.kw * p_weight_keyword +
         s.rec * p_weight_recency +
         s.imp * p_weight_importance +
         s.pop * p_weight_popularity)::REAL AS score_total
    FROM scored s
    WHERE s.sem > 0.3 OR s.kw > 0
    ORDER BY score_total DESC
    LIMIT p_limit;
END;
$$ LANGUAGE plpgsql;
"""

CHECK_DUPLICATE_SQL = """
DROP FUNCTION IF EXISTS check_duplicate_drawer(vector, real);
CREATE OR REPLACE FUNCTION check_duplicate_drawer(
    query_embedding vector({dim}),
    threshold REAL DEFAULT 0.95
)
RETURNS TABLE (
    id UUID,
    similarity REAL,
    summary TEXT
) AS $$
BEGIN
    RETURN QUERY
    SELECT sub.id, (1 - sub.dist)::REAL AS similarity, sub.summary
    FROM (
        SELECT d.id, d.summary,
               (d.embedding <=> query_embedding) AS dist
        FROM active_drawers d
        WHERE d.embedding IS NOT NULL
        ORDER BY d.embedding <=> query_embedding
        LIMIT 20
    ) sub
    WHERE (1 - sub.dist)::REAL > threshold
    ORDER BY sub.dist ASC
    LIMIT 5;
END;
$$ LANGUAGE plpgsql;
"""

VIEWS_SQL = """
CREATE OR REPLACE VIEW active_drawers AS
SELECT * FROM drawers
WHERE (valid_until IS NULL OR valid_until > now())
  AND status = 'committed';

CREATE OR REPLACE VIEW wing_stats AS
SELECT wing, hall, room,
       COUNT(*) as drawer_count,
       MIN(created_at) as first_entry,
       MAX(created_at) as last_entry
FROM active_drawers
GROUP BY wing, hall, room
ORDER BY wing, hall, room;
"""

async def check_and_recompute(pool: AsyncConnectionPool):
    """Detect model/dimension changes and recompute all embeddings if needed."""
    current_model = MODEL_NAME
    current_dim = get_dimension()

    # Get current state from identity table
    stored_model_row = await fetch_one(pool, "SELECT content FROM identity WHERE key = 'embedding_model'")
    stored_dim_row = await fetch_one(pool, "SELECT content FROM identity WHERE key = 'embedding_dimension'")

    stored_model = stored_model_row['content'] if stored_model_row else None
    stored_dim = int(stored_dim_row['content']) if stored_dim_row else None

    if stored_model == current_model and stored_dim == current_dim:
        logger.info("Embedding model and dimension match stored state. No recomputation needed.")
        return

    logger.info(f"Model/dimension change detected: {stored_model}({stored_dim}) -> {current_model}({current_dim})")

    # Use a pg_advisory_lock(842101) to prevent parallel migration attempts
    async with pool.connection() as conn:
        await conn.execute("SELECT pg_advisory_lock(842101)")
        try:
            # Re-check inside lock to avoid race conditions
            cur = await conn.execute("SELECT content FROM identity WHERE key = 'embedding_model'")
            stored_model_row = await cur.fetchone()
            cur = await conn.execute("SELECT content FROM identity WHERE key = 'embedding_dimension'")
            stored_dim_row = await cur.fetchone()
            
            stored_model = stored_model_row['content'] if stored_model_row else None
            stored_dim = int(stored_dim_row['content']) if stored_dim_row else None
            
            if stored_model == current_model and stored_dim == current_dim:
                return

            # Pre-migration backup
            if os.environ.get("HIVEMEM_SKIP_BACKUP") != "1":
                logger.info("Performing pre-migration backup...")
                script_path = os.path.join(os.path.dirname(__file__), "..", "scripts", "hivemem-backup")
                subprocess.run(["/bin/bash", script_path], check=True)
            else:
                logger.info("Skipping backup (HIVEMEM_SKIP_BACKUP=1).")

            # Start migration
            logger.info("Starting schema migration...")
            async with conn.transaction():
                # DROP old index and column
                # Use CASCADE because views (active_drawers, wing_stats) depend on this column
                await conn.execute("DROP INDEX IF EXISTS idx_drawers_embedding")
                await conn.execute("ALTER TABLE drawers DROP COLUMN IF EXISTS embedding CASCADE")
                
                # ADD column with new dimension
                await conn.execute(f"ALTER TABLE drawers ADD COLUMN embedding vector({current_dim})")

                # Batch re-embedding
                logger.info("Re-embedding all drawers...")
                cur = await conn.execute("SELECT id, content FROM drawers")
                all_drawers = await cur.fetchall()
                
                BATCH_SIZE = 100
                for i in range(0, len(all_drawers), BATCH_SIZE):
                    batch = all_drawers[i:i + BATCH_SIZE]
                    update_data = []
                    for drawer in batch:
                        vec = encode(drawer['content'])
                        update_data.append((vec, drawer['id']))
                    
                    async with conn.cursor() as update_cur:
                        await update_cur.executemany(
                            "UPDATE drawers SET embedding = %s WHERE id = %s",
                            update_data
                        )

                # Post-migration: Recreate the HNSW index
                logger.info("Recreating HNSW index...")
                await conn.execute("CREATE INDEX idx_drawers_embedding ON drawers USING hnsw (embedding vector_cosine_ops)")

                # Update views and helper functions with new dimension
                await conn.execute(VIEWS_SQL)
                await conn.execute(RANKED_SEARCH_SQL.format(dim=current_dim))
                await conn.execute(CHECK_DUPLICATE_SQL.format(dim=current_dim))

                # Update 'identity' table
                await conn.execute(
                    "INSERT INTO identity (key, content) VALUES ('embedding_model', %s) "
                    "ON CONFLICT (key) DO UPDATE SET content = EXCLUDED.content, updated_at = now()",
                    (current_model,)
                )
                await conn.execute(
                    "INSERT INTO identity (key, content) VALUES ('embedding_dimension', %s) "
                    "ON CONFLICT (key) DO UPDATE SET content = EXCLUDED.content, updated_at = now()",
                    (str(current_dim),)
                )
            
            logger.info("Recomputation complete.")

        finally:
            await conn.execute("SELECT pg_advisory_unlock(842101)")
