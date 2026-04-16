-- Remove fixed dimension constraint from embedding column.
-- pgvector enforces dimensional consistency through the HNSW index.
-- This allows model changes without DDL at runtime.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'drawers' AND column_name = 'embedding'
    ) THEN
        ALTER TABLE drawers ALTER COLUMN embedding TYPE vector;
    END IF;
END $$;
