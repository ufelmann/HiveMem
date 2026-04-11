-- depends:

-- hivemem/schema.sql
-- HiveMem v2 — Append-only versioned knowledge system

-- ============================================================
-- CORE STORAGE (append-only)
-- ============================================================

CREATE TABLE IF NOT EXISTS drawers (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id   UUID REFERENCES drawers(id),
    content     TEXT NOT NULL,
    embedding   vector(1024),
    wing        TEXT,
    room        TEXT,
    hall        TEXT CHECK (hall IN ('facts','events','discoveries','preferences','advice')),
    source      TEXT,
    tags        TEXT[],
    importance  SMALLINT CHECK (importance BETWEEN 1 AND 5),
    summary     TEXT,
    status      TEXT NOT NULL DEFAULT 'committed'
                CHECK (status IN ('pending','committed','rejected')),
    created_by  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_from  TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_until TIMESTAMPTZ,
    tsv         tsvector GENERATED ALWAYS AS (
                    setweight(to_tsvector('english', coalesce(summary, '')), 'A') ||
                    setweight(to_tsvector('english', coalesce(content, '')), 'B')
                ) STORED
);

ALTER TABLE drawers ADD COLUMN IF NOT EXISTS key_points TEXT[];
ALTER TABLE drawers ADD COLUMN IF NOT EXISTS insight TEXT;
ALTER TABLE drawers ADD COLUMN IF NOT EXISTS actionability TEXT
    CHECK (actionability IN ('actionable', 'reference', 'someday', 'archive'));

CREATE TABLE IF NOT EXISTS facts (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id   UUID REFERENCES facts(id),
    subject     TEXT NOT NULL,
    predicate   TEXT NOT NULL,
    object      TEXT NOT NULL,
    confidence  REAL DEFAULT 1.0,
    source_id   UUID REFERENCES drawers(id) ON DELETE SET NULL,
    status      TEXT NOT NULL DEFAULT 'committed'
                CHECK (status IN ('pending','committed','rejected')),
    created_by  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_from  TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_until TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS edges (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    from_entity TEXT NOT NULL,
    to_entity   TEXT NOT NULL,
    relation    TEXT NOT NULL,
    weight      REAL DEFAULT 1.0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS identity (
    key         TEXT PRIMARY KEY,
    content     TEXT NOT NULL,
    token_count INTEGER,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================
-- INDEXES
-- ============================================================

CREATE INDEX IF NOT EXISTS idx_drawers_wing_room ON drawers (wing, room);
CREATE INDEX IF NOT EXISTS idx_drawers_hall ON drawers (hall);
CREATE INDEX IF NOT EXISTS idx_drawers_temporal ON drawers (valid_from, valid_until);
CREATE INDEX IF NOT EXISTS idx_drawers_source ON drawers (source);
CREATE INDEX IF NOT EXISTS idx_drawers_status ON drawers (status) WHERE status = 'pending';
CREATE INDEX IF NOT EXISTS idx_drawers_parent ON drawers (parent_id);
CREATE INDEX IF NOT EXISTS idx_drawers_tags ON drawers USING GIN (tags);

CREATE INDEX IF NOT EXISTS idx_facts_subj_pred ON facts (subject, predicate);
CREATE INDEX IF NOT EXISTS idx_facts_obj ON facts (object);
CREATE INDEX IF NOT EXISTS idx_facts_temporal ON facts (valid_from, valid_until);
CREATE INDEX IF NOT EXISTS idx_facts_source ON facts (source_id);
CREATE INDEX IF NOT EXISTS idx_facts_status ON facts (status) WHERE status = 'pending';
CREATE INDEX IF NOT EXISTS idx_facts_parent ON facts (parent_id);
CREATE INDEX IF NOT EXISTS idx_facts_created ON facts (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_edges_from ON edges (from_entity);
CREATE INDEX IF NOT EXISTS idx_edges_to ON edges (to_entity);
CREATE INDEX IF NOT EXISTS idx_edges_relation ON edges (relation);

-- ============================================================
-- VIEWS
-- ============================================================

CREATE OR REPLACE VIEW active_drawers AS
SELECT * FROM drawers
WHERE (valid_until IS NULL OR valid_until > now())
  AND status = 'committed';

CREATE OR REPLACE VIEW active_facts AS
SELECT * FROM facts
WHERE (valid_until IS NULL OR valid_until > now())
  AND status = 'committed';

CREATE OR REPLACE VIEW pending_approvals AS
SELECT 'drawer' as type, id, summary as description, wing, room, created_by, created_at
FROM drawers WHERE status = 'pending'
UNION ALL
SELECT 'fact' as type, id, subject || ' -> ' || predicate || ' -> ' || object, NULL, NULL, created_by, created_at
FROM facts WHERE status = 'pending'
ORDER BY created_at ASC;

CREATE OR REPLACE VIEW wing_stats AS
SELECT wing, room, hall,
       COUNT(*) as drawer_count,
       MIN(created_at) as first_entry,
       MAX(created_at) as last_entry
FROM active_drawers
GROUP BY wing, room, hall
ORDER BY wing, room, hall;

-- ============================================================
-- FUNCTIONS
-- ============================================================

CREATE OR REPLACE FUNCTION revise_drawer(
    p_old_id UUID,
    p_new_content TEXT,
    p_new_summary TEXT DEFAULT NULL,
    p_created_by TEXT DEFAULT 'user'
)
RETURNS UUID AS $$
DECLARE
    v_new_id UUID;
    v_old RECORD;
BEGIN
    UPDATE drawers SET valid_until = now()
    WHERE id = p_old_id AND valid_until IS NULL
    RETURNING * INTO v_old;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Drawer % not found or already revised', p_old_id;
    END IF;

    INSERT INTO drawers (parent_id, content, wing, room, hall, source, tags, importance, summary,
                         key_points, insight, actionability, status, created_by)
    VALUES (p_old_id, p_new_content, v_old.wing, v_old.room, v_old.hall, v_old.source, v_old.tags,
            v_old.importance, COALESCE(p_new_summary, v_old.summary),
            v_old.key_points, v_old.insight, v_old.actionability, 'committed', p_created_by)
    RETURNING id INTO v_new_id;
    RETURN v_new_id;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION revise_fact(
    p_old_id UUID,
    p_new_object TEXT,
    p_new_valid_from TIMESTAMPTZ DEFAULT now(),
    p_created_by TEXT DEFAULT 'user'
)
RETURNS UUID AS $$
DECLARE
    v_new_id UUID;
    v_old RECORD;
BEGIN
    UPDATE facts SET valid_until = p_new_valid_from
    WHERE id = p_old_id AND valid_until IS NULL
    RETURNING * INTO v_old;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Fact % not found or already revised', p_old_id;
    END IF;

    INSERT INTO facts (parent_id, subject, predicate, object, confidence, source_id, status, created_by, valid_from)
    VALUES (p_old_id, v_old.subject, v_old.predicate, p_new_object, v_old.confidence,
            v_old.source_id, 'committed', p_created_by, p_new_valid_from)
    RETURNING id INTO v_new_id;
    RETURN v_new_id;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION invalidate_fact(
    p_subject TEXT,
    p_predicate TEXT,
    p_object TEXT,
    p_ended TIMESTAMPTZ DEFAULT now()
)
RETURNS INTEGER AS $$
DECLARE
    updated_count INTEGER;
BEGIN
    UPDATE facts
    SET valid_until = p_ended
    WHERE subject = p_subject
      AND predicate = p_predicate
      AND object = p_object
      AND valid_until IS NULL;
    GET DIAGNOSTICS updated_count = ROW_COUNT;
    RETURN updated_count;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION check_contradiction(
    p_subject TEXT,
    p_predicate TEXT,
    p_new_object TEXT
)
RETURNS TABLE (
    fact_id UUID,
    existing_object TEXT,
    valid_from TIMESTAMPTZ
) AS $$
BEGIN
    RETURN QUERY
    SELECT f.id, f.object, f.valid_from
    FROM active_facts f
    WHERE f.subject = p_subject
      AND f.predicate = p_predicate
      AND f.object != p_new_object;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION drawer_history(p_id UUID)
RETURNS TABLE (
    id UUID,
    parent_id UUID,
    summary TEXT,
    created_by TEXT,
    valid_from TIMESTAMPTZ,
    valid_until TIMESTAMPTZ
) AS $$
BEGIN
    RETURN QUERY
    WITH RECURSIVE chain AS (
        SELECT d.id, d.parent_id, d.summary, d.created_by, d.valid_from, d.valid_until, 1 AS depth
        FROM drawers d WHERE d.id = p_id
        UNION ALL
        SELECT d.id, d.parent_id, d.summary, d.created_by, d.valid_from, d.valid_until, c.depth + 1
        FROM drawers d JOIN chain c ON d.id = c.parent_id
        WHERE c.depth < 100
    )
    SELECT chain.id, chain.parent_id, chain.summary, chain.created_by, chain.valid_from, chain.valid_until
    FROM chain ORDER BY chain.valid_from ASC;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION fact_history(p_id UUID)
RETURNS TABLE (
    id UUID,
    parent_id UUID,
    subject TEXT,
    predicate TEXT,
    object TEXT,
    created_by TEXT,
    valid_from TIMESTAMPTZ,
    valid_until TIMESTAMPTZ
) AS $$
BEGIN
    RETURN QUERY
    WITH RECURSIVE chain AS (
        SELECT f.id, f.parent_id, f.subject, f.predicate, f.object, f.created_by, f.valid_from, f.valid_until, 1 AS depth
        FROM facts f WHERE f.id = p_id
        UNION ALL
        SELECT f.id, f.parent_id, f.subject, f.predicate, f.object, f.created_by, f.valid_from, f.valid_until, c.depth + 1
        FROM facts f JOIN chain c ON f.id = c.parent_id
        WHERE c.depth < 100
    )
    SELECT chain.id, chain.parent_id, chain.subject, chain.predicate, chain.object, chain.created_by, chain.valid_from, chain.valid_until
    FROM chain ORDER BY chain.valid_from ASC;
END;
$$ LANGUAGE plpgsql;

-- ============================================================
-- RANKED SEARCH INFRASTRUCTURE
-- ============================================================

-- Full-text search index (tsv column defined in CREATE TABLE above)
CREATE INDEX IF NOT EXISTS idx_drawers_tsv ON drawers USING GIN (tsv);

-- HNSW vector index (works well at any scale, better recall than ivfflat)
CREATE INDEX IF NOT EXISTS idx_drawers_embedding ON drawers USING hnsw (embedding vector_cosine_ops);

-- Access tracking (popularity signal)
CREATE TABLE IF NOT EXISTS access_log (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    drawer_id   UUID REFERENCES drawers(id),
    fact_id     UUID REFERENCES facts(id),
    accessed_by TEXT,
    accessed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_access_drawer ON access_log (drawer_id, accessed_at DESC);
CREATE INDEX IF NOT EXISTS idx_access_fact ON access_log (fact_id, accessed_at DESC);

-- Materialized view: access counts per drawer (refresh periodically)
CREATE MATERIALIZED VIEW IF NOT EXISTS drawer_popularity AS
SELECT
    drawer_id,
    COUNT(*) AS access_count,
    MAX(accessed_at) AS last_accessed,
    COUNT(*) FILTER (WHERE accessed_at > now() - interval '30 days') AS recent_access_count
FROM access_log
WHERE drawer_id IS NOT NULL
GROUP BY drawer_id;

CREATE UNIQUE INDEX IF NOT EXISTS idx_drawer_popularity_id ON drawer_popularity (drawer_id);

-- Ranked search: 5 signals with configurable weights
CREATE OR REPLACE FUNCTION ranked_search(
    query_embedding vector(1024),
    query_text TEXT,
    p_wing TEXT DEFAULT NULL,
    p_room TEXT DEFAULT NULL,
    p_hall TEXT DEFAULT NULL,
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
    room TEXT,
    hall TEXT,
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
            d.id, d.content, d.summary, d.wing, d.room, d.hall,
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
          AND (p_room IS NULL OR d.room = p_room)
          AND (p_hall IS NULL OR d.hall = p_hall)
    )
    SELECT
        s.id, s.content, s.summary, s.wing, s.room, s.hall,
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

-- ============================================================
-- PROGRESSIVE SUMMARIZATION (L0-L3)
-- ============================================================

-- ============================================================
-- REFERENCES & READING LIST
-- ============================================================

CREATE TABLE IF NOT EXISTS references_ (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title       TEXT NOT NULL,
    url         TEXT,
    author      TEXT,
    ref_type    TEXT CHECK (ref_type IN ('article','paper','book','video','podcast','tweet','repo','conversation','internal','other')),
    status      TEXT NOT NULL DEFAULT 'read'
                CHECK (status IN ('unread','reading','read','archived')),
    notes       TEXT,
    tags        TEXT[],
    importance  SMALLINT CHECK (importance BETWEEN 1 AND 5),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS drawer_references (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    drawer_id    UUID NOT NULL REFERENCES drawers(id),
    reference_id UUID NOT NULL REFERENCES references_(id),
    relation     TEXT NOT NULL DEFAULT 'source'
                 CHECK (relation IN ('source','inspired_by','contradicts','extends')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_refs_status ON references_ (status) WHERE status IN ('unread', 'reading');
CREATE INDEX IF NOT EXISTS idx_refs_type ON references_ (ref_type);
CREATE INDEX IF NOT EXISTS idx_drawer_refs_drawer ON drawer_references (drawer_id);
CREATE INDEX IF NOT EXISTS idx_drawer_refs_ref ON drawer_references (reference_id);

-- Duplicate check: find drawers with embedding similarity > threshold
CREATE OR REPLACE FUNCTION check_duplicate_drawer(
    query_embedding vector(1024),
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

-- ============================================================
-- AGENT FLEET
-- ============================================================

CREATE TABLE IF NOT EXISTS agents (
    name        TEXT PRIMARY KEY,
    focus       TEXT NOT NULL,
    autonomy    JSONB NOT NULL DEFAULT '{"default":"suggest_only"}',
    schedule    TEXT,
    model_routing JSONB,
    tools       TEXT[],
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS agent_diary (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent       TEXT NOT NULL REFERENCES agents(name),
    entry       TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_diary_agent ON agent_diary (agent, created_at DESC);

-- ============================================================
-- GRAPH SEARCH
-- ============================================================

CREATE OR REPLACE FUNCTION quick_facts(p_entity TEXT)
RETURNS TABLE (
    id UUID,
    subject TEXT,
    predicate TEXT,
    object TEXT,
    confidence REAL,
    valid_from TIMESTAMPTZ
) AS $$
BEGIN
    RETURN QUERY
    SELECT f.id, f.subject, f.predicate, f.object, f.confidence, f.valid_from
    FROM active_facts f
    WHERE f.subject = p_entity OR f.object = p_entity
    ORDER BY f.valid_from DESC;
END;
$$ LANGUAGE plpgsql;

-- ============================================================
-- MAPS OF CONTENT
-- ============================================================

CREATE TABLE IF NOT EXISTS maps (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wing        TEXT NOT NULL,
    title       TEXT NOT NULL,
    narrative   TEXT NOT NULL,
    room_order  TEXT[],
    key_drawers UUID[],
    created_by  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_from  TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_until TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_maps_wing ON maps (wing);
CREATE INDEX IF NOT EXISTS idx_maps_temporal ON maps (valid_from, valid_until);

CREATE OR REPLACE VIEW active_maps AS
SELECT * FROM maps
WHERE valid_until IS NULL OR valid_until > now();

-- ============================================================
-- API TOKENS
-- ============================================================

CREATE TABLE IF NOT EXISTS api_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash  TEXT NOT NULL UNIQUE,
    name        TEXT NOT NULL UNIQUE,
    role        TEXT NOT NULL CHECK (role IN ('admin', 'writer', 'reader', 'agent')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_tokens_hash ON api_tokens (token_hash)
    WHERE revoked_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_tokens_name ON api_tokens (name);
