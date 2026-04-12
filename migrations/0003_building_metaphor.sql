-- depends: 0002_edges_v2

-- ============================================================
-- Building Metaphor Rename
-- Swap room <-> hall columns, rename edges -> tunnels, maps -> blueprints
-- ============================================================

-- 0. Drop views and functions whose return columns change
-- PostgreSQL cannot CREATE OR REPLACE these with changed column names
DROP VIEW IF EXISTS active_drawers CASCADE;
DROP VIEW IF EXISTS pending_approvals CASCADE;
DROP VIEW IF EXISTS wing_stats CASCADE;
DROP FUNCTION IF EXISTS ranked_search(vector,text,text,text,text,integer,real,real,real,real,real);

-- 0b. Drop the CHECK constraint on hall column BEFORE the swap
-- After swap, the old hall column (with the check) becomes room,
-- but room accepts free-form text, so the check must go
DO $$
DECLARE
    cons_name TEXT;
BEGIN
    SELECT conname INTO cons_name
    FROM pg_constraint
    WHERE conrelid = 'drawers'::regclass
      AND conname LIKE '%hall%check%' OR (conrelid = 'drawers'::regclass AND pg_get_constraintdef(oid) LIKE '%hall%IN%');

    IF cons_name IS NOT NULL THEN
        EXECUTE 'ALTER TABLE drawers DROP CONSTRAINT ' || cons_name;
    END IF;
END $$;

-- Also drop any unnamed check constraint on hall
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'drawers'::regclass
          AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%hall%'
    LOOP
        EXECUTE 'ALTER TABLE drawers DROP CONSTRAINT ' || r.conname;
    END LOOP;
END $$;

-- 1. Swap room <-> hall in drawers table
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'drawers' AND column_name = 'room'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'drawers' AND column_name = '_room_swap'
    ) THEN
        ALTER TABLE drawers RENAME COLUMN room TO _room_swap;
        ALTER TABLE drawers RENAME COLUMN hall TO room;
        ALTER TABLE drawers RENAME COLUMN _room_swap TO hall;
    END IF;
END $$;

-- 2. Rename edges -> tunnels
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'edges')
       AND NOT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'tunnels')
    THEN
        ALTER TABLE edges RENAME TO tunnels;
    END IF;
END $$;

-- 3. Rename indexes on tunnels (was edges)
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_edges_from') THEN
        ALTER INDEX idx_edges_from RENAME TO idx_tunnels_from;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_edges_to') THEN
        ALTER INDEX idx_edges_to RENAME TO idx_tunnels_to;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_edges_relation') THEN
        ALTER INDEX idx_edges_relation RENAME TO idx_tunnels_relation;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_edges_temporal') THEN
        ALTER INDEX idx_edges_temporal RENAME TO idx_tunnels_temporal;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_edges_status') THEN
        ALTER INDEX idx_edges_status RENAME TO idx_tunnels_status;
    END IF;
END $$;

-- 4. Rename maps -> blueprints
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'maps')
       AND NOT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'blueprints')
    THEN
        ALTER TABLE maps RENAME TO blueprints;
    END IF;
END $$;

-- 5. Rename room_order -> hall_order in blueprints
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'blueprints' AND column_name = 'room_order'
    ) THEN
        ALTER TABLE blueprints RENAME COLUMN room_order TO hall_order;
    END IF;
END $$;

-- 6. Rename indexes on blueprints (was maps)
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_maps_wing') THEN
        ALTER INDEX idx_maps_wing RENAME TO idx_blueprints_wing;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_maps_temporal') THEN
        ALTER INDEX idx_maps_temporal RENAME TO idx_blueprints_temporal;
    END IF;
END $$;

-- 7. Rename drawers indexes (room <-> hall swapped)
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_drawers_wing_room') THEN
        ALTER INDEX idx_drawers_wing_room RENAME TO idx_drawers_wing_hall;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_drawers_hall') THEN
        ALTER INDEX idx_drawers_hall RENAME TO idx_drawers_room;
    END IF;
END $$;

-- 8. Recreate views
DROP VIEW IF EXISTS active_edges;
DROP VIEW IF EXISTS active_maps;

CREATE OR REPLACE VIEW active_tunnels AS
SELECT * FROM tunnels
WHERE (valid_until IS NULL OR valid_until > now())
  AND status = 'committed';

CREATE OR REPLACE VIEW active_blueprints AS
SELECT * FROM blueprints
WHERE valid_until IS NULL OR valid_until > now();

CREATE OR REPLACE VIEW active_drawers AS
SELECT * FROM drawers
WHERE (valid_until IS NULL OR valid_until > now())
  AND status = 'committed';

CREATE OR REPLACE VIEW pending_approvals AS
SELECT 'drawer' as type, id, summary as description, wing, hall, created_by, created_at
FROM drawers WHERE status = 'pending'
UNION ALL
SELECT 'fact' as type, id, subject || ' -> ' || predicate || ' -> ' || object, NULL, NULL, created_by, created_at
FROM facts WHERE status = 'pending'
UNION ALL
SELECT 'tunnel' as type, id, from_drawer::text || ' -[' || relation || ']-> ' || to_drawer::text, NULL, NULL, created_by, created_at
FROM tunnels WHERE status = 'pending'
ORDER BY created_at ASC;

CREATE OR REPLACE VIEW wing_stats AS
SELECT wing, hall, room,
       COUNT(*) as drawer_count,
       MIN(created_at) as first_entry,
       MAX(created_at) as last_entry
FROM active_drawers
GROUP BY wing, hall, room
ORDER BY wing, hall, room;

-- 9. Recreate ranked_search with swapped hall/room params
CREATE OR REPLACE FUNCTION ranked_search(
    query_embedding vector(384),
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
            CASE WHEN d.embedding IS NOT NULL
                 THEN (1 - (d.embedding <=> query_embedding))::REAL
                 ELSE 0::REAL END
            AS sem,
            CASE WHEN query_text IS NOT NULL AND query_text != ''
                 THEN LEAST(ts_rank_cd(d.tsv, plainto_tsquery('english', query_text))::REAL, 1.0::REAL)
                 ELSE 0::REAL END
            AS kw,
            EXP(-0.693 * EXTRACT(EPOCH FROM (now() - d.created_at)) / (90 * 86400))::REAL
            AS rec,
            (CASE d.importance
                WHEN 1 THEN 1.0 WHEN 2 THEN 0.8 WHEN 3 THEN 0.6
                WHEN 4 THEN 0.4 WHEN 5 THEN 0.2 ELSE 0.6 END)::REAL
            AS imp,
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

-- 10. Recreate revise_drawer with swapped column names
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

    INSERT INTO drawers (parent_id, content, wing, hall, room, source, tags, importance, summary,
                         key_points, insight, actionability, status, created_by)
    VALUES (p_old_id, p_new_content, v_old.wing, v_old.hall, v_old.room, v_old.source, v_old.tags,
            v_old.importance, COALESCE(p_new_summary, v_old.summary),
            v_old.key_points, v_old.insight, v_old.actionability, 'committed', p_created_by)
    RETURNING id INTO v_new_id;
    RETURN v_new_id;
END;
$$ LANGUAGE plpgsql;
