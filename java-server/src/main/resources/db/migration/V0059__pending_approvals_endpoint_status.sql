-- V0059: hide pending tunnels whose endpoint cells are not committed.
--
-- The Queen page gained a single "accept all" button that commits every listed proposal in one
-- transaction. On the production data 14 of the 666 pending tunnels point at a cell that has
-- already been rejected -- reviewed one at a time a human would decline them, but a bulk accept
-- would commit them silently, and this UI offers no undo. A tunnel to a cell that was thrown
-- away carries no meaning either way, so it must not reach that button.
--
-- The filter therefore lists a pending tunnel only when BOTH endpoint cells are committed. It
-- lives on the tunnel branch only; the cell and fact branches are unchanged.
--
-- NULL semantics: the endpoint joins are LEFT JOINs, so a missing cells row yields status NULL,
-- and `cf.status = 'committed'` would then be NULL -- neither true nor false -- which WHERE
-- treats as "not matched" only by accident of three-valued logic. coalesce(..., '') makes the
-- predicate a real boolean, so a missing row is excluded deliberately rather than incidentally.
-- (A FK on tunnels.from_cell/to_cell makes that row impossible today; the guard is defensive.)
--
-- The trade-off, decided explicitly and NOT to be "fixed" back: these tunnels stay 'pending' in
-- the database forever and become invisible in this UI -- nothing else lists them, so they can
-- no longer be decided from the product at all. That was chosen over the alternative of marking
-- them on the card, because the bulk-accept button is irreversible and an unreviewable proposal
-- on screen is a trap. Deciding them again requires a direct UPDATE on tunnels.status.
--
-- Why DROP + CREATE rather than CREATE OR REPLACE: identical shape to V0058, which this view
-- otherwise reproduces verbatim. V0058 must not be edited -- it has already run.

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
  AND coalesce(cf.status, '') = 'committed'
  AND coalesce(ct.status, '') = 'committed'
ORDER BY created_at ASC;
