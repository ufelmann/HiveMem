-- Search, embedding, and admin support for the Java migration track.

ALTER TABLE drawers
    ADD COLUMN IF NOT EXISTS embedding REAL[];

CREATE TABLE IF NOT EXISTS access_log (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    drawer_id   UUID REFERENCES drawers(id) ON DELETE CASCADE,
    fact_id     UUID REFERENCES facts(id) ON DELETE CASCADE,
    accessed_by TEXT NOT NULL DEFAULT 'system',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (drawer_id IS NOT NULL OR fact_id IS NOT NULL)
);

CREATE MATERIALIZED VIEW IF NOT EXISTS drawer_popularity AS
SELECT d.id AS drawer_id,
       count(al.id)::BIGINT AS access_count
FROM drawers d
LEFT JOIN access_log al ON al.drawer_id = d.id
GROUP BY d.id;

CREATE UNIQUE INDEX IF NOT EXISTS drawer_popularity_drawer_id_idx
    ON drawer_popularity (drawer_id);
