-- Page statistics per consumed batch. degraded_pages counts pages whose vision metadata
-- extraction failed both attempts and fell back to an all-null row: those pages contribute
-- nothing to document boundary detection, and separation confidence cannot detect them
-- because the assembler scores its own grouping, not the completeness of its input.
ALTER TABLE consumption_file ADD COLUMN total_pages    integer;
ALTER TABLE consumption_file ADD COLUMN degraded_pages integer;
