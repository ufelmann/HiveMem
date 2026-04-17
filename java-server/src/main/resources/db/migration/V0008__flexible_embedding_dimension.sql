-- Remove fixed dimension constraint from embedding column.
-- pgvector enforces dimensional consistency through the HNSW index.
-- This allows model changes without DDL at runtime.
--
-- active_drawers and wing_stats depend on the embedding column via SELECT *,
-- so they must be dropped and recreated around the ALTER.

DO $$
BEGIN
    -- atttypmod > 0 means the column has a fixed dimension (e.g. vector(1024));
    -- atttypmod = -1 means it is already the unconstrained vector type.
    IF EXISTS (
        SELECT 1 FROM pg_attribute a
        JOIN pg_class c ON c.oid = a.attrelid
        WHERE c.relname = 'drawers' AND a.attname = 'embedding'
          AND a.attnum > 0 AND NOT a.attisdropped
          AND a.atttypmod > 0
    ) THEN
        DROP VIEW IF EXISTS wing_stats;
        DROP VIEW IF EXISTS active_drawers;

        ALTER TABLE drawers ALTER COLUMN embedding TYPE vector;

        CREATE VIEW active_drawers AS
        SELECT *
        FROM drawers
        WHERE (valid_until IS NULL OR valid_until > now())
          AND status = 'committed';

        CREATE OR REPLACE VIEW wing_stats AS
        SELECT wing, hall, room,
               COUNT(*) AS drawer_count,
               MIN(created_at) AS first_entry,
               MAX(created_at) AS last_entry
        FROM active_drawers
        GROUP BY wing, hall, room
        ORDER BY wing, hall, room;
    END IF;
END $$;
