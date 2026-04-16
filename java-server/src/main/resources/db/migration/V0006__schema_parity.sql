-- Full schema parity with Python reference schema.
-- All statements are idempotent (IF NOT EXISTS / CREATE OR REPLACE).

-- Extensions
CREATE EXTENSION IF NOT EXISTS vector SCHEMA public;

-- tsv generated column
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'drawers' AND column_name = 'tsv'
    ) THEN
        ALTER TABLE drawers ADD COLUMN tsv tsvector GENERATED ALWAYS AS (
            setweight(to_tsvector('english', coalesce(summary, '')), 'A') ||
            setweight(to_tsvector('english', coalesce(content, '')), 'B')
        ) STORED;
    END IF;
END $$;

-- Drawer indexes
CREATE INDEX IF NOT EXISTS idx_drawers_wing_hall ON drawers (wing, hall);
CREATE INDEX IF NOT EXISTS idx_drawers_room ON drawers (room);
CREATE INDEX IF NOT EXISTS idx_drawers_temporal ON drawers (valid_from, valid_until);
CREATE INDEX IF NOT EXISTS idx_drawers_source ON drawers (source);
CREATE INDEX IF NOT EXISTS idx_drawers_status ON drawers (status) WHERE status = 'pending';
CREATE INDEX IF NOT EXISTS idx_drawers_parent ON drawers (parent_id);
CREATE INDEX IF NOT EXISTS idx_drawers_tags ON drawers USING GIN (tags);
CREATE INDEX IF NOT EXISTS idx_drawers_tsv ON drawers USING GIN (tsv);

-- Fact indexes
CREATE INDEX IF NOT EXISTS idx_facts_subj_pred ON facts (subject, predicate);
CREATE INDEX IF NOT EXISTS idx_facts_obj ON facts ("object");
CREATE INDEX IF NOT EXISTS idx_facts_temporal ON facts (valid_from, valid_until);
CREATE INDEX IF NOT EXISTS idx_facts_source ON facts (source_id);
CREATE INDEX IF NOT EXISTS idx_facts_status ON facts (status) WHERE status = 'pending';
CREATE INDEX IF NOT EXISTS idx_facts_parent ON facts (parent_id);
CREATE INDEX IF NOT EXISTS idx_facts_created ON facts (created_at DESC);

-- Tunnel indexes
CREATE INDEX IF NOT EXISTS idx_tunnels_from ON tunnels (from_drawer) WHERE valid_until IS NULL;
CREATE INDEX IF NOT EXISTS idx_tunnels_to ON tunnels (to_drawer) WHERE valid_until IS NULL;
CREATE INDEX IF NOT EXISTS idx_tunnels_relation ON tunnels (relation) WHERE valid_until IS NULL;
CREATE INDEX IF NOT EXISTS idx_tunnels_temporal ON tunnels (valid_from, valid_until);
CREATE INDEX IF NOT EXISTS idx_tunnels_status ON tunnels (status) WHERE status = 'pending';

-- Access log indexes
CREATE INDEX IF NOT EXISTS idx_access_drawer ON access_log (drawer_id, accessed_at DESC);
CREATE INDEX IF NOT EXISTS idx_access_fact ON access_log (fact_id, accessed_at DESC);

-- Reference indexes
CREATE INDEX IF NOT EXISTS idx_refs_status ON references_ (status) WHERE status IN ('unread', 'reading');
CREATE INDEX IF NOT EXISTS idx_refs_type ON references_ (ref_type);
CREATE INDEX IF NOT EXISTS idx_drawer_refs_drawer ON drawer_references (drawer_id);
CREATE INDEX IF NOT EXISTS idx_drawer_refs_ref ON drawer_references (reference_id);

-- Agent diary index
CREATE INDEX IF NOT EXISTS idx_diary_agent ON agent_diary (agent, created_at DESC);

-- Blueprint indexes
CREATE INDEX IF NOT EXISTS idx_blueprints_wing ON blueprints (wing);
CREATE INDEX IF NOT EXISTS idx_blueprints_temporal ON blueprints (valid_from, valid_until);

-- Recreate active_drawers to pick up columns added after V0002 (embedding, tsv)
DROP VIEW IF EXISTS wing_stats;
DROP VIEW IF EXISTS active_drawers;
CREATE VIEW active_drawers AS
SELECT *
FROM drawers
WHERE (valid_until IS NULL OR valid_until > now())
  AND status = 'committed';

-- wing_stats view
CREATE OR REPLACE VIEW wing_stats AS
SELECT wing, hall, room,
       COUNT(*) AS drawer_count,
       MIN(created_at) AS first_entry,
       MAX(created_at) AS last_entry
FROM active_drawers
GROUP BY wing, hall, room
ORDER BY wing, hall, room;

-- PL/pgSQL: ranked_search
CREATE OR REPLACE FUNCTION ranked_search(
    query_embedding vector,
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
    id UUID, content TEXT, summary TEXT, wing TEXT, hall TEXT, room TEXT,
    tags TEXT[], importance SMALLINT, created_at TIMESTAMPTZ, valid_from TIMESTAMPTZ,
    score_semantic REAL, score_keyword REAL, score_recency REAL,
    score_importance REAL, score_popularity REAL, score_total REAL
) AS $$
BEGIN
    RETURN QUERY
    WITH max_pop AS (
        SELECT GREATEST(MAX(recent_access_count), 1)::REAL AS val FROM drawer_popularity
    ),
    scored AS (
        SELECT d.id, d.content, d.summary, d.wing, d.hall, d.room,
            d.tags, d.importance, d.created_at, d.valid_from,
            CASE WHEN d.embedding IS NOT NULL
                 THEN (1 - (d.embedding <=> query_embedding))::REAL ELSE 0::REAL END AS sem,
            CASE WHEN query_text IS NOT NULL AND query_text != ''
                 THEN LEAST(ts_rank_cd(d.tsv, plainto_tsquery('english', query_text))::REAL, 1.0::REAL)
                 ELSE 0::REAL END AS kw,
            EXP(-0.693 * EXTRACT(EPOCH FROM (now() - d.created_at)) / (90 * 86400))::REAL AS rec,
            (CASE d.importance
                WHEN 1 THEN 1.0 WHEN 2 THEN 0.8 WHEN 3 THEN 0.6
                WHEN 4 THEN 0.4 WHEN 5 THEN 0.2 ELSE 0.6 END)::REAL AS imp,
            COALESCE(dp.recent_access_count::REAL / (SELECT val FROM max_pop), 0)::REAL AS pop
        FROM drawers d
        LEFT JOIN drawer_popularity dp ON dp.drawer_id = d.id
        WHERE (d.valid_until IS NULL OR d.valid_until > now()) AND d.status = 'committed'
          AND (p_wing IS NULL OR d.wing = p_wing)
          AND (p_hall IS NULL OR d.hall = p_hall)
          AND (p_room IS NULL OR d.room = p_room)
    )
    SELECT s.id, s.content, s.summary, s.wing, s.hall, s.room,
           s.tags, s.importance, s.created_at, s.valid_from,
           s.sem, s.kw, s.rec, s.imp, s.pop,
           (s.sem * p_weight_semantic + s.kw * p_weight_keyword +
            s.rec * p_weight_recency + s.imp * p_weight_importance +
            s.pop * p_weight_popularity)::REAL AS score_total
    FROM scored s WHERE s.sem > 0.3 OR s.kw > 0
    ORDER BY score_total DESC LIMIT p_limit;
END;
$$ LANGUAGE plpgsql;

-- PL/pgSQL: check_duplicate_drawer
CREATE OR REPLACE FUNCTION check_duplicate_drawer(
    query_embedding vector, threshold REAL DEFAULT 0.95
)
RETURNS TABLE (id UUID, similarity REAL, summary TEXT) AS $$
BEGIN
    RETURN QUERY
    SELECT sub.id, (1 - sub.dist)::REAL AS similarity, sub.summary
    FROM (
        SELECT d.id, d.summary, (d.embedding <=> query_embedding) AS dist
        FROM active_drawers d WHERE d.embedding IS NOT NULL
        ORDER BY d.embedding <=> query_embedding LIMIT 20
    ) sub
    WHERE (1 - sub.dist)::REAL > threshold
    ORDER BY sub.dist ASC LIMIT 5;
END;
$$ LANGUAGE plpgsql;

-- PL/pgSQL: quick_facts
CREATE OR REPLACE FUNCTION quick_facts(p_entity TEXT)
RETURNS TABLE (id UUID, subject TEXT, predicate TEXT, object TEXT, confidence REAL, valid_from TIMESTAMPTZ) AS $$
BEGIN
    RETURN QUERY
    SELECT f.id, f.subject, f.predicate, f."object", f.confidence, f.valid_from
    FROM active_facts f WHERE f.subject = p_entity OR f."object" = p_entity
    ORDER BY f.valid_from DESC;
END;
$$ LANGUAGE plpgsql;
