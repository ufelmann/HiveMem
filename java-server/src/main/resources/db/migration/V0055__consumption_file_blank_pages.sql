-- Nullable so the existing rows (predating this column) are left unset rather than given an
-- invented value. Semantics: blank.size() in ReassemblyOrchestrator — ALL pages recognised as
-- blank, whether voted blank by the vision model or (future) skipped by a pixel check. Today,
-- without this column, a dropped blank page is only a log line; a batch that silently loses half
-- its pages to the blank-page filter is otherwise invisible in the ledger and the review queue.
ALTER TABLE consumption_file ADD COLUMN blank_pages integer;
