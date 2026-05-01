-- V0027: Document-Type-Klassifizierung pro Cell.
-- Wert ist der von einer ExtractionProfile-Registry bekannten Type-IDs (z.B. invoice, contract, other).
-- Kein Constraint — neue Profile können hinzugefügt werden, ohne Schema zu ändern.

ALTER TABLE cells ADD COLUMN document_type TEXT;

CREATE INDEX idx_cells_document_type ON cells (document_type)
    WHERE document_type IS NOT NULL;
