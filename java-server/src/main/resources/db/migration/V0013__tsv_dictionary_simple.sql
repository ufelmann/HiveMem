-- V0013: switch tsv dictionary from 'english' to 'simple'.
--
-- Background
-- ----------
-- HiveMem stores DE+EN mixed content (German user notes, English code/docs).
-- The previous 'english' dictionary stemmed and stopword-filtered with English
-- assumptions, so German queries lost matches and German stopwords like "der"/
-- "die"/"das" leaked through as significant tokens.
--
-- The 'simple' dictionary lowercases and tokenizes by non-alphanumeric without
-- stemming or stopword removal. Trade-off: English plurals no longer match the
-- singular ("cells" != "cell"). Acceptable given the language mix.
--
-- The tsv column is GENERATED ALWAYS, so changing the expression requires a
-- DROP COLUMN + ADD COLUMN cycle, which also drops the GIN index. We recreate
-- both. ranked_search() must be updated in lockstep so plainto_tsquery uses the
-- same dictionary.

-- active_cells (and realm_stats which depends on it) SELECT *, so they pin the
-- tsv column type. Drop them, swap the column, then recreate the views from
-- the V0010 definitions.
DROP VIEW IF EXISTS realm_stats;
DROP VIEW IF EXISTS active_cells;

ALTER TABLE cells DROP COLUMN tsv;
ALTER TABLE cells ADD COLUMN tsv tsvector GENERATED ALWAYS AS (
    setweight(to_tsvector('simple', coalesce(summary, '')), 'A') ||
    setweight(to_tsvector('simple', coalesce(content, '')), 'B')
) STORED;
CREATE INDEX IF NOT EXISTS idx_cells_tsv ON cells USING GIN (tsv);

CREATE VIEW active_cells AS
SELECT *
FROM cells
WHERE (valid_until IS NULL OR valid_until > now())
  AND status = 'committed';

CREATE VIEW realm_stats AS
SELECT realm, topic, signal,
       COUNT(*) AS cell_count,
       MIN(created_at) AS first_entry,
       MAX(created_at) AS last_entry
FROM active_cells
GROUP BY realm, topic, signal
ORDER BY realm, topic, signal;

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
                 THEN LEAST(ts_rank_cd(c.tsv, plainto_tsquery('simple', query_text))::REAL, 1.0::REAL)
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
