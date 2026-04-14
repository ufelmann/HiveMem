-- Search, embedding, and admin support for the Java migration track.

CREATE EXTENSION IF NOT EXISTS vector SCHEMA public;

ALTER TABLE drawers
    ADD COLUMN IF NOT EXISTS embedding vector(1024);

CREATE TABLE IF NOT EXISTS access_log (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    drawer_id   UUID REFERENCES drawers(id),
    fact_id     UUID REFERENCES facts(id),
    accessed_by TEXT NOT NULL DEFAULT 'system',
    accessed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE MATERIALIZED VIEW IF NOT EXISTS drawer_popularity AS
SELECT
    drawer_id,
    COUNT(*) AS access_count,
    MAX(accessed_at) AS last_accessed,
    COUNT(*) FILTER (WHERE accessed_at > now() - interval '30 days') AS recent_access_count
FROM access_log
WHERE drawer_id IS NOT NULL
GROUP BY drawer_id;

CREATE UNIQUE INDEX IF NOT EXISTS drawer_popularity_drawer_id_idx
    ON drawer_popularity (drawer_id);
