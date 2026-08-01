-- V0053: remove a stale HNSW index on cells.embedding left behind by the
-- drawer→cell rename (0dd028a). It exists only in deployed databases, never in
-- a migration, and its vector(384) cast aborts any re-encode to a new dimension.
DROP INDEX IF EXISTS idx_drawers_embedding;
