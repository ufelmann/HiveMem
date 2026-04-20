-- V0011: Finish the terminology rename inside the `blueprints` table.
--
-- V0010 renamed top-level tables and their primary hierarchy columns, but
-- left two array columns inside `blueprints` on the old vocabulary:
--
--   blueprints.hall_order  → signal_order
--   blueprints.key_drawers → key_cells
--
-- These surface through the MCP API (`hivemem_update_blueprint` args,
-- `hivemem_get_blueprint` output) and were documented with the new names
-- in README.md already.
--
-- This migration is idempotent: column renames are guarded by existence
-- checks so re-running against an already-migrated database is a no-op.

DROP VIEW IF EXISTS active_blueprints CASCADE;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'blueprints'
          AND column_name = 'hall_order'
    ) THEN
        ALTER TABLE blueprints RENAME COLUMN hall_order TO signal_order;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'blueprints'
          AND column_name = 'key_drawers'
    ) THEN
        ALTER TABLE blueprints RENAME COLUMN key_drawers TO key_cells;
    END IF;
END $$;

CREATE VIEW active_blueprints AS
SELECT *
FROM blueprints
WHERE valid_until IS NULL OR valid_until > now();
