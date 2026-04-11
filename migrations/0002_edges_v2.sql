-- depends: 0001_initial

-- Edges v2: drawer-to-drawer UUIDs with temporal versioning
-- Idempotent: only acts if old schema (from_entity column) is present

DO $$
BEGIN
    -- Check if old schema exists
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'edges' AND column_name = 'from_entity'
    ) THEN
        -- Drop old indexes
        DROP INDEX IF EXISTS idx_edges_from;
        DROP INDEX IF EXISTS idx_edges_to;
        DROP INDEX IF EXISTS idx_edges_relation;

        -- Drop old table (0 rows expected)
        DROP TABLE edges;

        -- Create new edges table
        CREATE TABLE edges (
            id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            from_drawer  UUID NOT NULL REFERENCES drawers(id),
            to_drawer    UUID NOT NULL REFERENCES drawers(id),
            relation     TEXT NOT NULL CHECK (relation IN ('related_to','builds_on','contradicts','refines')),
            note         TEXT,
            status       TEXT NOT NULL DEFAULT 'committed'
                         CHECK (status IN ('pending','committed','rejected')),
            created_by   TEXT NOT NULL DEFAULT 'system',
            valid_from   TIMESTAMPTZ NOT NULL DEFAULT now(),
            valid_until  TIMESTAMPTZ,
            created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
        );

        -- Partial indexes for GUI performance
        CREATE INDEX idx_edges_from ON edges (from_drawer) WHERE valid_until IS NULL;
        CREATE INDEX idx_edges_to ON edges (to_drawer) WHERE valid_until IS NULL;
        CREATE INDEX idx_edges_relation ON edges (relation) WHERE valid_until IS NULL;
        CREATE INDEX idx_edges_temporal ON edges (valid_from, valid_until);
        CREATE INDEX idx_edges_status ON edges (status) WHERE status = 'pending';
    END IF;
END $$;

-- Views (always recreate — idempotent)
CREATE OR REPLACE VIEW active_edges AS
SELECT * FROM edges
WHERE (valid_until IS NULL OR valid_until > now())
  AND status = 'committed';

CREATE OR REPLACE VIEW pending_approvals AS
SELECT 'drawer' as type, id, summary as description, wing, room, created_by, created_at
FROM drawers WHERE status = 'pending'
UNION ALL
SELECT 'fact' as type, id, subject || ' -> ' || predicate || ' -> ' || object, NULL, NULL, created_by, created_at
FROM facts WHERE status = 'pending'
UNION ALL
SELECT 'edge' as type, id, from_drawer::text || ' -[' || relation || ']-> ' || to_drawer::text, NULL, NULL, created_by, created_at
FROM edges WHERE status = 'pending'
ORDER BY created_at ASC;
