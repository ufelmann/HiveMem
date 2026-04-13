-- Java migration bootstrap baseline.
-- This deliberately starts small: it creates the minimum PostgreSQL objects
-- needed for the Java service to boot and proves the migration track is live.

CREATE TABLE IF NOT EXISTS migration_baseline (
    id SMALLINT PRIMARY KEY,
    note TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO migration_baseline (id, note)
VALUES (1, 'Java migration bootstrap baseline')
ON CONFLICT (id) DO NOTHING;
