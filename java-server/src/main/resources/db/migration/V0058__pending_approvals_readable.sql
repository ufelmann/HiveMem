-- V0058: make a pending proposal decidable by a human.
--
-- The tunnel branch of pending_approvals built its only text column out of the two foreign
-- keys (V0010:261), so the UI could offer nothing but two UUIDs and an Accept/Reject pair.
-- The information was there all along: every pending tunnel carries the proposing agent's
-- rationale in tunnels.note, and both endpoint cells carry realm/topic. This view keeps a
-- separate title (short label) and description (the rationale), and exposes the endpoint ids
-- so the UI can link to both cells.
--
-- Why a helper function: 3719 of the cells have topic IS NULL (and 3 have realm IS NULL), and
-- `realm || '/' || topic` yields NULL for the whole expression as soon as one operand is NULL.
-- An unguarded concatenation would render empty cards -- the same failure this migration fixes.
-- cell_label() takes the tunnel's own foreign key (never null) rather than the joined cell's id,
-- so it still produces a label when the endpoint row is gone entirely.
--
-- Why DROP + CREATE rather than CREATE OR REPLACE: Postgres only allows appending columns at the
-- end of a replaced view, and title belongs next to description. Nothing depends on this view.

CREATE OR REPLACE FUNCTION cell_label(p_realm TEXT, p_topic TEXT, p_id UUID)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT CASE
        WHEN p_realm IS NULL AND p_topic IS NULL THEN left(p_id::text, 8)
        WHEN p_realm IS NULL THEN p_topic
        ELSE p_realm || '/' || coalesce(p_topic, left(p_id::text, 8))
    END
$$;

DROP VIEW IF EXISTS pending_approvals;

CREATE VIEW pending_approvals AS
SELECT 'cell'::text AS type,
       c.id,
       cell_label(c.realm, c.topic, c.id) AS title,
       c.summary AS description,
       c.realm,
       c.signal,
       NULL::uuid AS from_cell,
       NULL::uuid AS to_cell,
       c.created_by,
       c.created_at
FROM cells c
WHERE c.status = 'pending'
UNION ALL
SELECT 'fact'::text AS type,
       f.id,
       f.subject || ' -> ' || f.predicate || ' -> ' || f."object" AS title,
       NULL::text AS description,
       NULL::text AS realm,
       NULL::text AS signal,
       NULL::uuid AS from_cell,
       NULL::uuid AS to_cell,
       f.created_by,
       f.created_at
FROM facts f
WHERE f.status = 'pending'
UNION ALL
SELECT 'tunnel'::text AS type,
       t.id,
       cell_label(cf.realm, cf.topic, t.from_cell)
           || ' -[' || t.relation || ']-> '
           || cell_label(ct.realm, ct.topic, t.to_cell) AS title,
       t.note AS description,
       cf.realm,
       NULL::text AS signal,
       t.from_cell,
       t.to_cell,
       t.created_by,
       t.created_at
FROM tunnels t
LEFT JOIN cells cf ON cf.id = t.from_cell
LEFT JOIN cells ct ON ct.id = t.to_cell
WHERE t.status = 'pending'
ORDER BY created_at ASC;
