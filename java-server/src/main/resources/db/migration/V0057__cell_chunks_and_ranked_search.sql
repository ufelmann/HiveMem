-- V0057: cell_chunks table (chunking design, design §3.2) plus the ranked_search change that
-- ranks by best matching chunk (design §3.6). The function part below is the Flyway fallback
-- copy of java-server/src/main/resources/db/templates/ranked_search.sql.tmpl (the authoritative,
-- runtime-rendered version) -- see V0056's header comment for why this only matters to
-- environments that migrate without ever booting the app, and why it must be re-diffed against
-- the template whenever the template's body changes.
--
-- Why md5(content) and not sha256(convert_to(content,'UTF8')):
-- convert_to is STABLE, not IMMUTABLE (its result depends on server encoding), so a generated
-- column built on it cannot be created:
--   CREATE TABLE probe (content text,
--     content_sha256 text GENERATED ALWAYS AS (encode(sha256(convert_to(content,'UTF8')),'hex')) STORED);
--   -- ERROR:  generation expression is not immutable
-- md5() is IMMUTABLE and in core. The hash is for change detection only, not security, so md5 is
-- sufficient and avoids an IMMUTABLE wrapper function plus the risk of forgetting to restore it.
--
-- cells.content_md5 is a generated, stored column (not computed ad hoc per sweep tick): without it
-- the sweep's staleness query would have to detoast and hash every long cell's content on every
-- tick (measured 28.9 ms at 407 cells; ~45 MB of decompression per tick at 5000 documents). With
-- it, staleness collapses to a column comparison.
--
-- cell_chunks.cell_content_hash (not "content_hash"): the column sits next to a column named
-- "content", so the obvious name would read as "hash of this chunk row's content" when it actually
-- means "hash of the CELL's content", copied redundantly onto every chunk row belonging to that
-- cell. It stays on the row as an integrity/debugging field, read from cells.content_md5 at INSERT
-- time same as before, but it is NOT the sweep's selection basis -- see chunked_content_md5 below.
--
-- cells.chunked_content_md5 is the "considered" marker, and the reason selection does not run a
-- NOT EXISTS against cell_chunks. Rule 6 (design §3.3) means 183 of 407 cells deliberately write NO
-- chunk row at all. For those, a NOT EXISTS predicate would stay permanently true: they would
-- re-enter every tick's batch, occupy its LIMIT slots, and could starve the cells that actually
-- need chunking -- the sweep's backlog would never terminate. chunked_content_md5 instead records
-- which content the sweep has LOOKED AT, independent of whether that produced any rows, and is set
-- in the same transaction as the chunk replacement (found while implementing this task, not by any
-- of the three review rounds that read the NOT EXISTS version).
--
-- No HNSW index here on purpose: EmbeddingStateRepository.createEmbeddingIndex creates the
-- existing indexes at runtime once the active dimension is known from the embedding service's
-- /info endpoint. A dimension nailed down by Flyway would break on the next model change, the same
-- failure V0053 already had to clean up once. EmbeddingStateRepository.createChunkEmbeddingIndex
-- follows the same pattern, called from EmbeddingMigrationService alongside the two existing
-- createEmbeddingIndex/createFactsEmbeddingIndex calls.
--
-- chunk_throttled_until lives on cells, not cell_chunks: a cell whose chunking/embedding failed has
-- NO chunk_chunks rows at all, so the throttle state has to survive on the row that outlives the
-- failed attempt. Modeled on cells.summarize_throttled_until.

ALTER TABLE cells ADD COLUMN content_md5 text
    GENERATED ALWAYS AS (md5(content)) STORED;

CREATE TABLE cell_chunks (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    cell_id           uuid NOT NULL REFERENCES cells(id) ON DELETE CASCADE,
    ordinal           integer NOT NULL,
    page_from         integer,
    page_to           integer,
    content           text NOT NULL,
    embedding         vector,
    cell_content_hash text NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (cell_id, ordinal)
);
CREATE INDEX idx_cell_chunks_cell ON cell_chunks (cell_id);

ALTER TABLE cells ADD COLUMN chunk_throttled_until timestamptz;
ALTER TABLE cells ADD COLUMN chunked_content_md5 text;

-- Same DROP-all-overloads pattern as V0056 (matching by name only, no pronamespace filter --
-- see V0056's header comment for why).
DO $do$
DECLARE r RECORD;
BEGIN
    FOR r IN SELECT oid::regprocedure AS sig FROM pg_proc
             WHERE proname = 'ranked_search' LOOP
        EXECUTE 'DROP FUNCTION ' || r.sig;
    END LOOP;
END
$do$;

CREATE FUNCTION ranked_search(
    query_embedding vector,
    query_text TEXT,
    p_realm TEXT DEFAULT NULL,
    p_signal TEXT DEFAULT NULL,
    p_topic TEXT DEFAULT NULL,
    p_limit INTEGER DEFAULT 10,
    p_weight_semantic REAL DEFAULT 0.30,
    p_weight_keyword REAL DEFAULT 0.15,
    p_weight_recency REAL DEFAULT 0.15,
    p_weight_importance REAL DEFAULT 0.15,
    p_weight_popularity REAL DEFAULT 0.15,
    p_weight_graph_proximity REAL DEFAULT 0.10,
    p_tags TEXT[] DEFAULT NULL,
    p_status TEXT DEFAULT NULL,
    p_relation_weights JSONB DEFAULT
        '{"builds_on":1.0,"refines":0.8,"related_to":0.6,"contradicts":0.4}'::jsonb,
    p_graph_max_depth INT DEFAULT 2,
    p_realms TEXT[] DEFAULT NULL
)
RETURNS TABLE (
    id UUID, content TEXT, summary TEXT, realm TEXT, signal TEXT, topic TEXT,
    tags TEXT[], importance SMALLINT, key_points TEXT[], insight TEXT,
    created_at TIMESTAMPTZ, valid_from TIMESTAMPTZ,
    valid_until TIMESTAMPTZ,
    score_semantic REAL, score_keyword REAL, score_recency REAL,
    score_importance REAL, score_popularity REAL, score_graph_proximity REAL,
    score_total REAL,
    match_page_from INTEGER, match_page_to INTEGER, match_excerpt TEXT
)
-- hnsw.ef_search is set as a function-level GUC (design §3.6a): LIMIT 400 alone pushes the
-- planner's HNSW startup cost estimate past the seq-scan crossover at the default ef_search=40,
-- so without this the chunk_ann ANN prefilter below silently degrades to a seq scan (measured
-- 53.9 ms vs 4.9 ms). A function-level SET, unlike a session SET, does not depend on the caller.
LANGUAGE SQL STABLE SET hnsw.ef_search = 500 AS $$
    WITH q AS (
        SELECT to_tsquery('simple',
               (SELECT string_agg('''' || replace(lex, '''', '''''') || '''', ' | ')
                FROM unnest(tsvector_to_array(to_tsvector('simple', coalesce(query_text, '')))) AS lex)
        ) AS tsq
    ),
    -- Chunk ANN prefilter, deliberately WITHOUT a join to cells (design §3.6a): joining here
    -- forces the planner off the HNSW index (measured 108 ms vs 4.9 ms with the join moved into
    -- `ann` below, where it only ever runs against chunk_best's <=400 rows instead of the full
    -- cell_chunks table). left(ch.content, 301), not 300: CellSearchRepository turns this into
    -- "300 chars + ellipsis" and needs the 301st character to tell "exactly 300" from "longer".
    chunk_ann AS (
        SELECT ch.cell_id, ch.ordinal, ch.page_from, ch.page_to,
               left(ch.content, 301) AS chunk_content,
               (1 - ((ch.embedding::vector(1024)) <=> query_embedding))::REAL AS chunk_sem
        FROM cell_chunks ch
        WHERE query_embedding IS NOT NULL AND ch.embedding IS NOT NULL
        ORDER BY (ch.embedding::vector(1024)) <=> query_embedding
        LIMIT 400
    ),
    -- Best chunk per cell among the 400 ANN candidates above. `, ordinal` tiebreaks a tied
    -- chunk_sem deterministically (lowest ordinal wins) -- without it, which of two equally
    -- scoring chunks supplies match_page_from/_to/_excerpt is arbitrary and can vary run to run.
    chunk_best AS (
        SELECT DISTINCT ON (cell_id) cell_id, page_from, page_to, chunk_content, chunk_sem
        FROM chunk_ann ORDER BY cell_id, chunk_sem DESC, ordinal
    ),
    ann AS (
        -- Both arms are parenthesized: the first arm's ORDER BY/LIMIT would otherwise be parsed
        -- as applying to the whole UNION, which Postgres rejects as a syntax error.
        (
            SELECT c.id
            FROM cells c
            WHERE (c.valid_until IS NULL OR c.valid_until > now())
              AND (p_status = 'all' OR c.status = COALESCE(p_status, 'committed'))
              AND c.embedding IS NOT NULL
              AND query_embedding IS NOT NULL
              AND (p_realm IS NULL OR (p_realm = 'none' AND c.realm IS NULL) OR (p_realm <> 'none' AND c.realm = p_realm))
              AND (p_realms IS NULL
                   OR c.realm = ANY(array_remove(p_realms, 'none'))
                   OR ('none' = ANY(p_realms) AND c.realm IS NULL))
              AND (p_signal IS NULL OR c.signal = p_signal)
              AND (p_topic IS NULL OR c.topic = p_topic)
              AND (p_tags IS NULL OR c.tags && p_tags)
            ORDER BY (c.embedding::vector(1024)) <=> query_embedding
            LIMIT 200
        )

        UNION

        -- The chunk branch of `ann` (design §3.6b): joins chunk_best (<=400 rows, already
        -- ANN-narrowed) to cells so the same seven filter predicates as the cell-vector branch
        -- above apply here too. Without this, a cell reachable only through its chunk could
        -- bypass a realm/signal/topic/tags/status/valid_until filter that would have excluded it
        -- via the cell-vector branch -- §5.3 tests exactly that parity.
        (
            SELECT c.id
            FROM chunk_best cb
            JOIN cells c ON c.id = cb.cell_id
            WHERE (c.valid_until IS NULL OR c.valid_until > now())
              AND (p_status = 'all' OR c.status = COALESCE(p_status, 'committed'))
              AND (p_realm IS NULL OR (p_realm = 'none' AND c.realm IS NULL) OR (p_realm <> 'none' AND c.realm = p_realm))
              AND (p_realms IS NULL
                   OR c.realm = ANY(array_remove(p_realms, 'none'))
                   OR ('none' = ANY(p_realms) AND c.realm IS NULL))
              AND (p_signal IS NULL OR c.signal = p_signal)
              AND (p_topic IS NULL OR c.topic = p_topic)
              AND (p_tags IS NULL OR c.tags && p_tags)
        )
    ),
    kw AS (
        SELECT c.id
        FROM cells c
        CROSS JOIN q
        WHERE (c.valid_until IS NULL OR c.valid_until > now())
          AND (p_status = 'all' OR c.status = COALESCE(p_status, 'committed'))
          AND q.tsq IS NOT NULL AND c.tsv @@ q.tsq
          AND (p_realm IS NULL OR (p_realm = 'none' AND c.realm IS NULL) OR (p_realm <> 'none' AND c.realm = p_realm))
          AND (p_realms IS NULL
               OR c.realm = ANY(array_remove(p_realms, 'none'))
               OR ('none' = ANY(p_realms) AND c.realm IS NULL))
          AND (p_signal IS NULL OR c.signal = p_signal)
          AND (p_topic IS NULL OR c.topic = p_topic)
          AND (p_tags IS NULL OR c.tags && p_tags)
        ORDER BY ts_rank_cd(c.tsv, q.tsq, 32) DESC
        LIMIT 200
    ),
    candidates AS (
        SELECT id FROM ann
        UNION
        SELECT id FROM kw
    ),
    -- Anchors sort by the same GREATEST(chunk, cell) expression as `scored` and no longer
    -- require c.embedding IS NOT NULL (design §3.6f): a cell reachable only through its chunk
    -- must be eligible to seed graph_proximity_scores, or the graph channel keeps reinforcing
    -- the pre-chunking ranking for exactly the cells chunking was meant to help find.
    anchors AS (
        SELECT c.id
        FROM cells c
        JOIN candidates ca ON ca.id = c.id
        LEFT JOIN chunk_best cb ON cb.cell_id = c.id
        WHERE query_embedding IS NOT NULL
          AND (c.embedding IS NOT NULL OR cb.chunk_sem IS NOT NULL)
        -- `, c.id` tiebreaks a tied GREATEST score deterministically -- anchors previously had a
        -- total ordering (ties broken by the cell-vector distance operator's own stability),
        -- which this explicit rule restores now that two cells can tie exactly on GREATEST.
        ORDER BY GREATEST(
            COALESCE(cb.chunk_sem, 0::REAL),
            CASE WHEN c.embedding IS NOT NULL AND query_embedding IS NOT NULL
                 THEN (1 - ((c.embedding::vector(1024)) <=> query_embedding))::REAL
                 ELSE 0::REAL END
        ) DESC, c.id
        LIMIT 25
    ),
    graph AS (
        SELECT cell_id, score
        FROM graph_proximity_scores(
            (SELECT array_agg(id) FROM anchors),
            p_relation_weights,
            p_graph_max_depth
        )
    ),
    scored AS (
        SELECT c.id, c.content, c.summary, c.realm, c.signal, c.topic,
            c.tags, c.importance, c.key_points, c.insight, c.created_at, c.valid_from, c.valid_until,
            -- sem = GREATEST(chunk, cell), never a replacement (design §3.6c): replacing the cell
            -- vector could push a cell below the `sem > 0.3 OR kw > 0` threshold in the final
            -- WHERE and drop it from the result set entirely. GREATEST can only raise the value.
            GREATEST(COALESCE(cb.chunk_sem, 0::REAL), cs.cell_sem) AS sem,
            CASE WHEN q.tsq IS NOT NULL THEN ts_rank_cd(c.tsv, q.tsq, 32)::REAL
                 ELSE 0::REAL END AS kw,
            EXP(-0.693 * EXTRACT(EPOCH FROM (now() - c.created_at)) / (90 * 86400))::REAL AS rec,
            (CASE c.importance
                WHEN 5 THEN 1.0 WHEN 4 THEN 0.8 WHEN 3 THEN 0.6
                WHEN 2 THEN 0.4 WHEN 1 THEN 0.2 ELSE 0.6 END)::REAL AS imp,
            LEAST(COALESCE(cp.recent_access_count, 0)::REAL / 25.0, 1.0)::REAL AS pop,
            COALESCE(g.score, 0)::REAL AS gp,
            -- match_* columns (design §3.6d): NULL unless the chunk actually contributed to sem,
            -- i.e. chunk_sem is present and >= the cell vector's own similarity (cs.cell_sem, the
            -- single definition computed once by the LATERAL below instead of being repeated).
            -- At an exact tie the chunk counts as the provider (>=, not >): the value is identical
            -- either way, so reporting the more informative match location is the more useful
            -- reading.
            CASE WHEN cb.chunk_sem IS NOT NULL AND cb.chunk_sem >= cs.cell_sem
                 THEN cb.page_from ELSE NULL END AS match_page_from,
            CASE WHEN cb.chunk_sem IS NOT NULL AND cb.chunk_sem >= cs.cell_sem
                 THEN cb.page_to ELSE NULL END AS match_page_to,
            CASE WHEN cb.chunk_sem IS NOT NULL AND cb.chunk_sem >= cs.cell_sem
                 THEN cb.chunk_content ELSE NULL END AS match_excerpt
        FROM cells c
        CROSS JOIN q
        JOIN candidates ca ON ca.id = c.id
        LEFT JOIN cell_popularity cp ON cp.cell_id = c.id
        LEFT JOIN graph g ON g.cell_id = c.id
        LEFT JOIN chunk_best cb ON cb.cell_id = c.id
        CROSS JOIN LATERAL (
            SELECT CASE WHEN c.embedding IS NOT NULL AND query_embedding IS NOT NULL
                        THEN (1 - ((c.embedding::vector(1024)) <=> query_embedding))::REAL
                        ELSE 0::REAL END AS cell_sem
        ) cs
    )
    SELECT s.id, s.content, s.summary, s.realm, s.signal, s.topic,
           s.tags, s.importance, s.key_points, s.insight, s.created_at, s.valid_from, s.valid_until,
           s.sem, s.kw, s.rec, s.imp, s.pop, s.gp,
           (s.sem * p_weight_semantic + s.kw * p_weight_keyword +
            s.rec * p_weight_recency + s.imp * p_weight_importance +
            s.pop * p_weight_popularity +
            s.gp  * p_weight_graph_proximity)::REAL AS score_total,
           s.match_page_from, s.match_page_to, s.match_excerpt
    FROM scored s WHERE s.sem > 0.3 OR s.kw > 0
    ORDER BY score_total DESC, s.id ASC LIMIT p_limit;
$$;
