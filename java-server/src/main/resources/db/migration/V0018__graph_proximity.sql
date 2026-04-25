-- Issue #25: graph proximity as 6th search signal.
-- Returns one row per neighbour of any anchor in `anchors`, with an aggregated
-- score = max over paths of (relation_weight * (1 / depth)). Anchors themselves
-- are excluded so they don't double-count against their own ranking signal.

CREATE OR REPLACE FUNCTION graph_proximity_scores(
    anchors UUID[],
    relation_weights JSONB,
    max_depth INT DEFAULT 2
)
RETURNS TABLE (cell_id UUID, score REAL)
LANGUAGE SQL STABLE AS $$
    WITH RECURSIVE walk(cell_id, depth, path_score) AS (
        SELECT a, 0, 1.0::REAL
        FROM unnest(anchors) AS a
        UNION ALL
        SELECT t.to_cell,
               w.depth + 1,
               (w.path_score
                 * COALESCE((relation_weights ->> t.relation)::REAL, 0.0::REAL)
                 * (1.0::REAL / (w.depth + 1)))::REAL
        FROM walk w
        JOIN tunnels t
          ON t.from_cell = w.cell_id
         AND t.status = 'committed'
         AND (t.valid_until IS NULL OR t.valid_until > now())
        WHERE w.depth < max_depth
    )
    SELECT cell_id, MAX(path_score)::REAL AS score
    FROM walk
    WHERE depth > 0                          -- exclude anchors
      AND NOT (cell_id = ANY(anchors))       -- never boost an anchor
    GROUP BY cell_id;
$$;
