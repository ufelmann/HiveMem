-- Read-tool slice schema for status, search_kg, and get_drawer.

CREATE TABLE IF NOT EXISTS drawers (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id   UUID REFERENCES drawers(id),
    content     TEXT NOT NULL,
    wing        TEXT,
    hall        TEXT,
    room        TEXT,
    source      TEXT,
    tags        TEXT[],
    importance  SMALLINT,
    summary     TEXT,
    key_points  TEXT[],
    insight     TEXT,
    actionability TEXT,
    status      TEXT NOT NULL DEFAULT 'committed'
                CHECK (status IN ('pending','committed','rejected')),
    created_by  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_from  TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_until TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS facts (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id   UUID REFERENCES facts(id),
    subject     TEXT NOT NULL,
    predicate   TEXT NOT NULL,
    "object"    TEXT NOT NULL,
    confidence  REAL DEFAULT 1.0,
    source_id   UUID REFERENCES drawers(id) ON DELETE SET NULL,
    status      TEXT NOT NULL DEFAULT 'committed'
                CHECK (status IN ('pending','committed','rejected')),
    created_by  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_from  TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_until TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS tunnels (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    from_drawer   UUID NOT NULL REFERENCES drawers(id),
    to_drawer     UUID NOT NULL REFERENCES drawers(id),
    relation      TEXT NOT NULL
                  CHECK (relation IN ('related_to','builds_on','contradicts','refines')),
    note          TEXT,
    status        TEXT NOT NULL DEFAULT 'committed'
                  CHECK (status IN ('pending','committed','rejected')),
    created_by    TEXT NOT NULL DEFAULT 'system',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_from    TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_until   TIMESTAMPTZ
);

CREATE OR REPLACE VIEW active_drawers AS
SELECT *
FROM drawers
WHERE (valid_until IS NULL OR valid_until > now())
  AND status = 'committed';

CREATE OR REPLACE VIEW active_facts AS
SELECT *
FROM facts
WHERE (valid_until IS NULL OR valid_until > now())
  AND status = 'committed';

CREATE OR REPLACE VIEW active_tunnels AS
SELECT *
FROM tunnels
WHERE (valid_until IS NULL OR valid_until > now())
  AND status = 'committed';

CREATE OR REPLACE VIEW pending_approvals AS
SELECT 'drawer'::text AS type,
       id,
       summary AS description,
       wing,
       hall,
       created_by,
       created_at
FROM drawers
WHERE status = 'pending'
UNION ALL
SELECT 'fact'::text AS type,
       id,
       subject || ' -> ' || predicate || ' -> ' || "object" AS description,
       NULL::text AS wing,
       NULL::text AS hall,
       created_by,
       created_at
FROM facts
WHERE status = 'pending'
UNION ALL
SELECT 'tunnel'::text AS type,
       id,
       from_drawer::text || ' -[' || relation || ']-> ' || to_drawer::text AS description,
       NULL::text AS wing,
       NULL::text AS hall,
       created_by,
       created_at
FROM tunnels
WHERE status = 'pending'
ORDER BY created_at ASC;
