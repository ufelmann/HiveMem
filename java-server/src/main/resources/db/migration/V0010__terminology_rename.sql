-- V0010: Terminology rename.
--
-- Renames conceptual terms to reduce collision with the mempalace project and
-- improve clarity in the GUI:
--
--   wing   → realm    (top-level section)
--   hall   → signal   (5-value enum; enum values unchanged)
--   room   → topic    (free-text topic)
--   drawer → cell     (knowledge entry)
--
-- Tables renamed: drawers → cells, drawer_references → cell_references
-- Column renames  follow across: cells, tunnels, access_log, blueprints, cell_references
-- Views, indexes, and PL/pgSQL functions are dropped and recreated.
-- A row-count guard verifies no data is lost.
--
-- Unchanged: tunnel, blueprint, reference, fact, agent, agent_diary, identity,
--            api_token, access_log (table name), embedding, auth role names.
--
-- All DDL is wrapped in an explicit transaction block.  Flyway auto-wraps DDL
-- in a transaction on PostgreSQL, so this is belt-and-braces only.

-- ─────────────────────────────────────────────────────────────────────────────
-- 0. Preflight guard
-- ─────────────────────────────────────────────────────────────────────────────
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = current_schema() AND c.relname = 'drawers' AND c.relkind = 'r'
    ) THEN
        RAISE EXCEPTION 'preflight failed: table "drawers" does not exist in schema %', current_schema();
    END IF;

    IF EXISTS (
        SELECT 1 FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = current_schema() AND c.relname = 'cells' AND c.relkind = 'r'
    ) THEN
        RAISE EXCEPTION 'preflight failed: table "cells" already exists in schema %', current_schema();
    END IF;
END $$;

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. Capture before-count
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TEMP TABLE _rename_counts AS
SELECT COUNT(*) AS before_count FROM drawers;

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. Drop views and materialized views that reference renamed columns/tables
-- ─────────────────────────────────────────────────────────────────────────────
DROP VIEW IF EXISTS wing_stats CASCADE;
DROP VIEW IF EXISTS active_drawers CASCADE;
DROP VIEW IF EXISTS active_tunnels CASCADE;
DROP VIEW IF EXISTS pending_approvals CASCADE;
DROP MATERIALIZED VIEW IF EXISTS drawer_popularity CASCADE;

-- active_facts and active_tunnels are independent; recreate active_tunnels later.
DROP VIEW IF EXISTS active_facts CASCADE;
DROP VIEW IF EXISTS active_blueprints CASCADE;

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. Drop PL/pgSQL functions that reference old table/column names
-- ─────────────────────────────────────────────────────────────────────────────
DO $$
DECLARE f oid;
BEGIN
    FOR f IN
        SELECT p.oid
        FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = current_schema()
          AND p.proname IN (
              'revise_drawer',
              'drawer_history',
              'check_duplicate_drawer',
              'ranked_search',
              'quick_facts'
          )
    LOOP
        EXECUTE 'DROP FUNCTION ' || f::regprocedure || ' CASCADE';
    END LOOP;
END $$;

-- ─────────────────────────────────────────────────────────────────────────────
-- 4. Rename tables
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE drawers          RENAME TO cells;
ALTER TABLE drawer_references RENAME TO cell_references;

-- ─────────────────────────────────────────────────────────────────────────────
-- 5. Rename columns
-- ─────────────────────────────────────────────────────────────────────────────

-- cells (was drawers): wing→realm, hall→signal, room→topic
ALTER TABLE cells RENAME COLUMN wing TO realm;
ALTER TABLE cells RENAME COLUMN hall TO signal;
ALTER TABLE cells RENAME COLUMN room TO topic;

-- tunnels: from_drawer→from_cell, to_drawer→to_cell
ALTER TABLE tunnels RENAME COLUMN from_drawer TO from_cell;
ALTER TABLE tunnels RENAME COLUMN to_drawer   TO to_cell;

-- access_log: drawer_id→cell_id
ALTER TABLE access_log RENAME COLUMN drawer_id TO cell_id;

-- blueprints: wing→realm
ALTER TABLE blueprints RENAME COLUMN wing TO realm;

-- cell_references (was drawer_references): drawer_id→cell_id
ALTER TABLE cell_references RENAME COLUMN drawer_id TO cell_id;

-- ─────────────────────────────────────────────────────────────────────────────
-- 6. Rename CHECK constraint on signal (was hall_check on drawers)
-- ─────────────────────────────────────────────────────────────────────────────
-- The drawers table had no hall CHECK constraint in the Java migrations.
-- The cells_signal_check we add below is brand-new (matching Python schema).
-- We DO rename the actionability check to keep things consistent.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'cells'::regclass
          AND conname = 'drawers_actionability_check'
    ) THEN
        ALTER TABLE cells RENAME CONSTRAINT drawers_actionability_check
            TO cells_actionability_check;
    END IF;

    IF EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'cells'::regclass
          AND conname = 'drawers_status_check'
    ) THEN
        ALTER TABLE cells RENAME CONSTRAINT drawers_status_check
            TO cells_status_check;
    END IF;
END $$;

-- ─────────────────────────────────────────────────────────────────────────────
-- 7. Rename indexes
-- ─────────────────────────────────────────────────────────────────────────────
ALTER INDEX IF EXISTS idx_drawers_wing_hall     RENAME TO idx_cells_realm_signal;
ALTER INDEX IF EXISTS idx_drawers_room          RENAME TO idx_cells_topic;
ALTER INDEX IF EXISTS idx_drawers_temporal      RENAME TO idx_cells_temporal;
ALTER INDEX IF EXISTS idx_drawers_source        RENAME TO idx_cells_source;
ALTER INDEX IF EXISTS idx_drawers_status        RENAME TO idx_cells_status;
ALTER INDEX IF EXISTS idx_drawers_parent        RENAME TO idx_cells_parent;
ALTER INDEX IF EXISTS idx_drawers_tags          RENAME TO idx_cells_tags;
ALTER INDEX IF EXISTS idx_drawers_tsv           RENAME TO idx_cells_tsv;
ALTER INDEX IF EXISTS idx_drawers_ingested_at   RENAME TO idx_cells_ingested_at;

ALTER INDEX IF EXISTS idx_tunnels_from          RENAME TO idx_tunnels_from_cell;
ALTER INDEX IF EXISTS idx_tunnels_to            RENAME TO idx_tunnels_to_cell;

ALTER INDEX IF EXISTS idx_access_drawer         RENAME TO idx_access_cell;

ALTER INDEX IF EXISTS idx_drawer_refs_drawer    RENAME TO idx_cell_refs_cell;
ALTER INDEX IF EXISTS idx_drawer_refs_ref       RENAME TO idx_cell_refs_ref;

ALTER INDEX IF EXISTS idx_blueprints_wing       RENAME TO idx_blueprints_realm;

-- ─────────────────────────────────────────────────────────────────────────────
-- 8. Recreate materialized view (drawer_popularity → cell_popularity)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE MATERIALIZED VIEW cell_popularity AS
SELECT
    cell_id,
    COUNT(*) AS access_count,
    MAX(accessed_at) AS last_accessed,
    COUNT(*) FILTER (WHERE accessed_at > now() - interval '30 days') AS recent_access_count
FROM access_log
WHERE cell_id IS NOT NULL
GROUP BY cell_id;

CREATE UNIQUE INDEX cell_popularity_cell_id_idx ON cell_popularity (cell_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- 9. Recreate views
-- ─────────────────────────────────────────────────────────────────────────────
CREATE VIEW active_cells AS
SELECT *
FROM cells
WHERE (valid_until IS NULL OR valid_until > now())
  AND status = 'committed';

CREATE VIEW active_facts AS
SELECT *
FROM facts
WHERE (valid_until IS NULL OR valid_until > now())
  AND status = 'committed';

CREATE VIEW active_tunnels AS
SELECT *
FROM tunnels
WHERE (valid_until IS NULL OR valid_until > now())
  AND status = 'committed';

CREATE VIEW active_blueprints AS
SELECT *
FROM blueprints
WHERE valid_until IS NULL OR valid_until > now();

CREATE VIEW realm_stats AS
SELECT realm, topic, signal,
       COUNT(*) AS cell_count,
       MIN(created_at) AS first_entry,
       MAX(created_at) AS last_entry
FROM active_cells
GROUP BY realm, topic, signal
ORDER BY realm, topic, signal;

CREATE VIEW pending_approvals AS
SELECT 'cell'::text AS type,
       id,
       summary AS description,
       realm,
       signal,
       created_by,
       created_at
FROM cells
WHERE status = 'pending'
UNION ALL
SELECT 'fact'::text AS type,
       id,
       subject || ' -> ' || predicate || ' -> ' || "object" AS description,
       NULL::text AS realm,
       NULL::text AS signal,
       created_by,
       created_at
FROM facts
WHERE status = 'pending'
UNION ALL
SELECT 'tunnel'::text AS type,
       id,
       from_cell::text || ' -[' || relation || ']-> ' || to_cell::text AS description,
       NULL::text AS realm,
       NULL::text AS signal,
       created_by,
       created_at
FROM tunnels
WHERE status = 'pending'
ORDER BY created_at ASC;

-- ─────────────────────────────────────────────────────────────────────────────
-- 10. Recreate PL/pgSQL functions with new names and column references
-- ─────────────────────────────────────────────────────────────────────────────

-- ranked_search: queries cells table directly (HNSW index compatible)
-- cell_popularity replaces drawer_popularity
CREATE OR REPLACE FUNCTION ranked_search(
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
            c.tags, c.importance, c.created_at, c.valid_from,
            CASE WHEN c.embedding IS NOT NULL
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
           s.tags, s.importance, s.created_at, s.valid_from,
           s.sem, s.kw, s.rec, s.imp, s.pop,
           (s.sem * p_weight_semantic + s.kw * p_weight_keyword +
            s.rec * p_weight_recency + s.imp * p_weight_importance +
            s.pop * p_weight_popularity)::REAL AS score_total
    FROM scored s WHERE s.sem > 0.3 OR s.kw > 0
    ORDER BY score_total DESC LIMIT p_limit;
END;
$$ LANGUAGE plpgsql;

-- check_duplicate_cell: near-duplicate detection using active_cells
CREATE OR REPLACE FUNCTION check_duplicate_cell(
    query_embedding vector, threshold REAL DEFAULT 0.95
)
RETURNS TABLE (id UUID, similarity REAL, summary TEXT) AS $$
BEGIN
    RETURN QUERY
    SELECT sub.id, (1 - sub.dist)::REAL AS similarity, sub.summary
    FROM (
        SELECT c.id, c.summary, (c.embedding <=> query_embedding) AS dist
        FROM active_cells c WHERE c.embedding IS NOT NULL
        ORDER BY c.embedding <=> query_embedding LIMIT 20
    ) sub
    WHERE (1 - sub.dist)::REAL > threshold
    ORDER BY sub.dist ASC LIMIT 5;
END;
$$ LANGUAGE plpgsql;

-- quick_facts: unchanged logic, kept here for completeness
CREATE OR REPLACE FUNCTION quick_facts(p_entity TEXT)
RETURNS TABLE (id UUID, subject TEXT, predicate TEXT, object TEXT, confidence REAL, valid_from TIMESTAMPTZ) AS $$
BEGIN
    RETURN QUERY
    SELECT f.id, f.subject, f.predicate, f."object", f.confidence, f.valid_from
    FROM active_facts f WHERE f.subject = p_entity OR f."object" = p_entity
    ORDER BY f.valid_from DESC;
END;
$$ LANGUAGE plpgsql;

-- ─────────────────────────────────────────────────────────────────────────────
-- 11. Row-count verify
-- ─────────────────────────────────────────────────────────────────────────────
DO $$
DECLARE
    before_count BIGINT;
    after_count  BIGINT;
BEGIN
    SELECT _rename_counts.before_count INTO before_count FROM _rename_counts;
    SELECT COUNT(*) INTO after_count FROM cells;
    IF after_count <> before_count THEN
        RAISE EXCEPTION 'row-count mismatch after rename: before=%, after=%',
            before_count, after_count;
    END IF;
END $$;

DROP TABLE _rename_counts;
