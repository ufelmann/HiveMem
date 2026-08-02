-- Corrected state set for consumption_file.state, superseding the comment in V0036 (which is
-- already applied everywhere and must not be edited): staged | processing | done | failed.
--   staged     - registered in the ledger, file not yet moved out of the watch root / not yet
--                picked up by a worker. Does NOT increment `attempts`.
--   processing - a worker has started reading the file; heartbeats bump updated_at per page.
--   done       - committed (or handed over to consumption_jobs at separation dispatch).
--   failed     - ingest error, or (via the recovery sweep) a stale row with no physical file.
--
-- Page statistics per consumed batch. degraded_pages counts pages whose vision metadata
-- extraction failed both attempts and fell back to an all-null row: those pages contribute
-- nothing to document boundary detection, and separation confidence cannot detect them
-- because the assembler scores its own grouping, not the completeness of its input.
ALTER TABLE consumption_file ADD COLUMN total_pages    integer;
ALTER TABLE consumption_file ADD COLUMN degraded_pages integer;
