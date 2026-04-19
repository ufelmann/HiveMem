-- V0009: Bi-temporal model.
--
-- Adds `ingested_at` (transaction time: when HiveMem learned of the row)
-- alongside the existing `valid_from` / `valid_until` pair (event time: when
-- the fact is true in reality). This enables queries of the form
-- "what did I know at time X" in addition to the existing "what was true
-- at time X".
--
-- Backfill strategy: for existing rows, ingested_at = created_at. From this
-- migration onwards, INSERTs default to NOW() and revisions receive a fresh
-- ingested_at (not inherited from parent_id), preserving transaction semantics.

ALTER TABLE drawers ADD COLUMN IF NOT EXISTS ingested_at TIMESTAMPTZ;
UPDATE drawers SET ingested_at = created_at WHERE ingested_at IS NULL;
ALTER TABLE drawers ALTER COLUMN ingested_at SET NOT NULL;
ALTER TABLE drawers ALTER COLUMN ingested_at SET DEFAULT NOW();

ALTER TABLE facts ADD COLUMN IF NOT EXISTS ingested_at TIMESTAMPTZ;
UPDATE facts SET ingested_at = created_at WHERE ingested_at IS NULL;
ALTER TABLE facts ALTER COLUMN ingested_at SET NOT NULL;
ALTER TABLE facts ALTER COLUMN ingested_at SET DEFAULT NOW();

ALTER TABLE tunnels ADD COLUMN IF NOT EXISTS ingested_at TIMESTAMPTZ;
UPDATE tunnels SET ingested_at = created_at WHERE ingested_at IS NULL;
ALTER TABLE tunnels ALTER COLUMN ingested_at SET NOT NULL;
ALTER TABLE tunnels ALTER COLUMN ingested_at SET DEFAULT NOW();

CREATE INDEX IF NOT EXISTS idx_drawers_ingested_at ON drawers (ingested_at);
CREATE INDEX IF NOT EXISTS idx_facts_ingested_at ON facts (ingested_at);
CREATE INDEX IF NOT EXISTS idx_tunnels_ingested_at ON tunnels (ingested_at);

-- Views use SELECT *, which freezes the column list at creation time.
-- Drop and recreate so ingested_at becomes visible to downstream queries.
-- wing_stats depends on active_drawers, so it must be dropped first.
DROP VIEW IF EXISTS wing_stats;
DROP VIEW IF EXISTS active_drawers;
DROP VIEW IF EXISTS active_facts;
DROP VIEW IF EXISTS active_tunnels;

CREATE VIEW active_drawers AS
SELECT *
FROM drawers
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

CREATE VIEW wing_stats AS
SELECT wing, hall, room,
       COUNT(*) AS drawer_count,
       MIN(created_at) AS first_entry,
       MAX(created_at) AS last_entry
FROM active_drawers
GROUP BY wing, hall, room
ORDER BY wing, hall, room;
