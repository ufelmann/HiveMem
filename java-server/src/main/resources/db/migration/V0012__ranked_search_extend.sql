-- V0012: ranked_search returns valid_until and is deterministically ordered.
--
-- Background
-- ----------
-- Until V0011, ranked_search() did the 5-signal scoring in SQL but Java still did
-- in-memory ranking via ReadToolService.search. Switching the Java path to call
-- ranked_search() directly requires two SQL-side changes:
--
--   1. Extend the return shape with valid_until (consumed by Java result map).
--   2. Add a stable ORDER BY id tiebreak so equal scores stay deterministic.
--
-- HNSW index on cells.embedding is intentionally NOT added here: V0008 dropped the
-- fixed-dim constraint on the embedding column to allow embedding model swaps at
-- runtime, and pgvector requires a fixed dimensionality at index build time. That
-- index belongs in a dedicated migration once we re-pin the active embedding dim
-- (or use a partial expression index with explicit cast).

-- CREATE OR REPLACE cannot change RETURNS TABLE columns, so drop first.
DROP FUNCTION IF EXISTS ranked_search(vector, TEXT, TEXT, TEXT, TEXT, INTEGER, REAL, REAL, REAL, REAL, REAL);

CREATE FUNCTION ranked_search(
    query_embedding vector,
    query_text TEXT,
    p_realm TEXT DEFAULT NULL,
    p_signal TEXT DEFAULT NULL,
    p_topic TEXT DEFAULT NULL,
    p_limit INTEGER DEFAULT 10,
    p_weight_semantic REAL DEFAULT 0.35,
    p_weight_keyword REAL DEFAULT 0.15,
    p_weight_recency REAL DEFAULT 0.20,
    p_weight_importance REAL DEFAULT 0.15,
    p_weight_popularity REAL DEFAULT 0.15
)
RETURNS TABLE (
    id UUID, content TEXT, summary TEXT, realm TEXT, signal TEXT, topic TEXT,
    tags TEXT[], importance SMALLINT, created_at TIMESTAMPTZ, valid_from TIMESTAMPTZ,
    valid_until TIMESTAMPTZ,
    score_semantic REAL, score_keyword REAL, score_recency REAL,
    score_importance REAL, score_popularity REAL, score_total REAL
) AS $$
BEGIN
    RETURN QUERY
    WITH max_pop AS (
        SELECT GREATEST(MAX(recent_access_count), 1)::REAL AS val FROM cell_popularity
    ),
    scored AS (
        SELECT c.id, c.content, c.summary, c.realm, c.signal, c.topic,
            c.tags, c.importance, c.created_at, c.valid_from, c.valid_until,
            CASE WHEN c.embedding IS NOT NULL AND query_embedding IS NOT NULL
                 THEN (1 - (c.embedding <=> query_embedding))::REAL ELSE 0::REAL END AS sem,
            CASE WHEN query_text IS NOT NULL AND query_text != ''
                 THEN LEAST(ts_rank_cd(c.tsv, plainto_tsquery('english', query_text))::REAL, 1.0::REAL)
                 ELSE 0::REAL END AS kw,
            EXP(-0.693 * EXTRACT(EPOCH FROM (now() - c.created_at)) / (90 * 86400))::REAL AS rec,
            (CASE c.importance
                WHEN 1 THEN 1.0 WHEN 2 THEN 0.8 WHEN 3 THEN 0.6
                WHEN 4 THEN 0.4 WHEN 5 THEN 0.2 ELSE 0.6 END)::REAL AS imp,
            COALESCE(cp.recent_access_count::REAL / (SELECT val FROM max_pop), 0)::REAL AS pop
        FROM cells c
        LEFT JOIN cell_popularity cp ON cp.cell_id = c.id
        WHERE (c.valid_until IS NULL OR c.valid_until > now()) AND c.status = 'committed'
          AND (p_realm IS NULL OR c.realm = p_realm)
          AND (p_signal IS NULL OR c.signal = p_signal)
          AND (p_topic IS NULL OR c.topic = p_topic)
    )
    SELECT s.id, s.content, s.summary, s.realm, s.signal, s.topic,
           s.tags, s.importance, s.created_at, s.valid_from, s.valid_until,
           s.sem, s.kw, s.rec, s.imp, s.pop,
           (s.sem * p_weight_semantic + s.kw * p_weight_keyword +
            s.rec * p_weight_recency + s.imp * p_weight_importance +
            s.pop * p_weight_popularity)::REAL AS score_total
    FROM scored s WHERE s.sem > 0.3 OR s.kw > 0
    ORDER BY score_total DESC, s.id ASC LIMIT p_limit;
END;
$$ LANGUAGE plpgsql;
