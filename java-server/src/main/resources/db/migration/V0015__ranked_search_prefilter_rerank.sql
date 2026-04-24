-- V0015: rewrite ranked_search as prefilter + rerank so HNSW can accelerate
-- candidate selection.
--
-- Why
-- ---
-- V0014 made ranked_search inlinable, but it still scores every committed cell
-- that matches the realm/signal/topic filter, then sorts by a computed
-- 5-signal score. HNSW only accelerates ORDER BY embedding <=> query LIMIT N,
-- not ORDER BY a composite score, so the cast + inlining alone were not
-- enough to avoid a full scan.
--
-- The new shape:
--
--   1. ann prefilter — top-N nearest neighbours by cosine distance (uses the
--      idx_cells_embedding expression HNSW index via the matching cast).
--   2. kw prefilter — rows that match the tsvector keyword query (uses the
--      idx_cells_tsv GIN index).
--   3. candidates — UNION of both prefilters; bounded by 2 * PREFILTER_SIZE.
--   4. scored — full 5-signal rank over just the candidate set.
--   5. ORDER BY score_total DESC, id ASC LIMIT p_limit with the V0013 hard
--      filter (sem > 0.3 OR kw > 0).
--
-- Prefilter size is 200 by default. That is ample for top-10 queries across
-- any reasonable weight combination; we can make it a parameter later if
-- callers need to tune recall.

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
)
LANGUAGE SQL STABLE AS $$
    WITH ann AS (
        SELECT c.id
        FROM cells c
        WHERE (c.valid_until IS NULL OR c.valid_until > now())
          AND c.status = 'committed'
          AND c.embedding IS NOT NULL
          AND query_embedding IS NOT NULL
          AND (p_realm IS NULL OR c.realm = p_realm)
          AND (p_signal IS NULL OR c.signal = p_signal)
          AND (p_topic IS NULL OR c.topic = p_topic)
        ORDER BY (c.embedding::vector(1024)) <=> query_embedding
        LIMIT 200
    ),
    kw AS (
        SELECT c.id
        FROM cells c
        WHERE (c.valid_until IS NULL OR c.valid_until > now())
          AND c.status = 'committed'
          AND query_text IS NOT NULL AND query_text != ''
          AND c.tsv @@ plainto_tsquery('simple', query_text)
          AND (p_realm IS NULL OR c.realm = p_realm)
          AND (p_signal IS NULL OR c.signal = p_signal)
          AND (p_topic IS NULL OR c.topic = p_topic)
        LIMIT 200
    ),
    candidates AS (
        SELECT id FROM ann
        UNION
        SELECT id FROM kw
    ),
    max_pop AS (
        SELECT GREATEST(MAX(recent_access_count), 1)::REAL AS val FROM cell_popularity
    ),
    scored AS (
        SELECT c.id, c.content, c.summary, c.realm, c.signal, c.topic,
            c.tags, c.importance, c.created_at, c.valid_from, c.valid_until,
            CASE WHEN c.embedding IS NOT NULL AND query_embedding IS NOT NULL
                 THEN (1 - ((c.embedding::vector(1024)) <=> query_embedding))::REAL
                 ELSE 0::REAL END AS sem,
            CASE WHEN query_text IS NOT NULL AND query_text != ''
                 THEN LEAST(ts_rank_cd(c.tsv, plainto_tsquery('simple', query_text))::REAL, 1.0::REAL)
                 ELSE 0::REAL END AS kw,
            EXP(-0.693 * EXTRACT(EPOCH FROM (now() - c.created_at)) / (90 * 86400))::REAL AS rec,
            (CASE c.importance
                WHEN 1 THEN 1.0 WHEN 2 THEN 0.8 WHEN 3 THEN 0.6
                WHEN 4 THEN 0.4 WHEN 5 THEN 0.2 ELSE 0.6 END)::REAL AS imp,
            COALESCE(cp.recent_access_count::REAL / (SELECT val FROM max_pop), 0)::REAL AS pop
        FROM cells c
        JOIN candidates ca ON ca.id = c.id
        LEFT JOIN cell_popularity cp ON cp.cell_id = c.id
    )
    SELECT s.id, s.content, s.summary, s.realm, s.signal, s.topic,
           s.tags, s.importance, s.created_at, s.valid_from, s.valid_until,
           s.sem, s.kw, s.rec, s.imp, s.pop,
           (s.sem * p_weight_semantic + s.kw * p_weight_keyword +
            s.rec * p_weight_recency + s.imp * p_weight_importance +
            s.pop * p_weight_popularity)::REAL AS score_total
    FROM scored s WHERE s.sem > 0.3 OR s.kw > 0
    ORDER BY score_total DESC, s.id ASC LIMIT p_limit;
$$;
