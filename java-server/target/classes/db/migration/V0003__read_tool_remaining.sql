-- Remaining read-tool schema for references, agents, diary, blueprints, and identity.

CREATE TABLE IF NOT EXISTS identity (
    key         TEXT PRIMARY KEY,
    content     TEXT NOT NULL,
    token_count INTEGER,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS references_ (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title       TEXT NOT NULL,
    url         TEXT,
    author      TEXT,
    ref_type    TEXT CHECK (ref_type IN ('article','paper','book','video','podcast','tweet','repo','conversation','internal','other')),
    status      TEXT NOT NULL DEFAULT 'read'
                CHECK (status IN ('unread','reading','read','archived')),
    notes       TEXT,
    tags        TEXT[],
    importance  SMALLINT CHECK (importance BETWEEN 1 AND 5),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS drawer_references (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    drawer_id    UUID NOT NULL REFERENCES drawers(id),
    reference_id  UUID NOT NULL REFERENCES references_(id),
    relation     TEXT NOT NULL DEFAULT 'source'
                 CHECK (relation IN ('source','inspired_by','contradicts','extends')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS agents (
    name           TEXT PRIMARY KEY,
    focus          TEXT NOT NULL,
    autonomy       JSONB NOT NULL DEFAULT '{"default":"suggest_only"}'::jsonb,
    schedule       TEXT,
    model_routing  JSONB,
    tools          TEXT[],
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS agent_diary (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent       TEXT NOT NULL REFERENCES agents(name),
    entry       TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS blueprints (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wing         TEXT NOT NULL,
    title        TEXT NOT NULL,
    narrative    TEXT NOT NULL,
    hall_order   TEXT[],
    key_drawers  UUID[],
    created_by   TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_from   TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_until  TIMESTAMPTZ
);

CREATE OR REPLACE VIEW active_blueprints AS
SELECT *
FROM blueprints
WHERE valid_until IS NULL OR valid_until > now();
