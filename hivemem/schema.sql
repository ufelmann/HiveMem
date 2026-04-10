-- hivemem/schema.sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS age;

CREATE TABLE IF NOT EXISTS drawers (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content     TEXT NOT NULL,
    embedding   vector(1024),
    wing        TEXT,
    room        TEXT,
    hall        TEXT,
    source      TEXT,
    tags        TEXT[],
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_from  TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_until TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS facts (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subject     TEXT NOT NULL,
    predicate   TEXT NOT NULL,
    object      TEXT NOT NULL,
    confidence  REAL DEFAULT 1.0,
    source_id   UUID REFERENCES drawers(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_from  TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_until TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS edges (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    from_entity TEXT NOT NULL,
    to_entity   TEXT NOT NULL,
    relation    TEXT NOT NULL,
    weight      REAL DEFAULT 1.0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS identity (
    key         TEXT PRIMARY KEY,
    content     TEXT NOT NULL,
    token_count INTEGER,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_drawers_wing_room ON drawers (wing, room);
CREATE INDEX IF NOT EXISTS idx_drawers_temporal ON drawers (valid_from, valid_until);
CREATE INDEX IF NOT EXISTS idx_facts_subj_pred ON facts (subject, predicate);
CREATE INDEX IF NOT EXISTS idx_facts_temporal ON facts (valid_from, valid_until);
CREATE INDEX IF NOT EXISTS idx_edges_from ON edges (from_entity);
CREATE INDEX IF NOT EXISTS idx_edges_to ON edges (to_entity);
