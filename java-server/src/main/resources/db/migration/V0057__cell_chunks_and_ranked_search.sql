-- V0057: cell_chunks table (chunking design, design §3.2).
--
-- This file's function part (ranked_search's chunk_ann/chunk_best/GREATEST changes) is added by a
-- later task; this migration deliberately contains ONLY the table part.
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
-- cell. Under the wrong reading, NOT EXISTS in the sweep's selection query would always be true for
-- any multi-chunk cell and the sweep would loop forever re-chunking it.
--
-- No HNSW index here on purpose: EmbeddingStateRepository.createEmbeddingIndex creates the
-- existing indexes at runtime once the active dimension is known from the embedding service's
-- /info endpoint. A dimension nailed down by Flyway would break on the next model change, the same
-- failure V0053 already had to clean up once. createChunkEmbeddingIndex follows the same pattern
-- starting with a later task.
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
