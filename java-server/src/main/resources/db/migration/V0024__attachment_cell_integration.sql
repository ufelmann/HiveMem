-- V0024__attachment_cell_integration.sql

-- 1. Drop extracted_text — content now lives in Cell
ALTER TABLE attachments DROP COLUMN IF EXISTS extracted_text;

-- 2. Remove V0023 ref_type='attachment' hack from references_
-- Clean up any V0023 attachment-hack rows before tightening constraints
DELETE FROM references_ WHERE ref_type = 'attachment';

ALTER TABLE references_
    DROP CONSTRAINT IF EXISTS references__ref_type_check;
ALTER TABLE references_
    ADD CONSTRAINT references__ref_type_check
        CHECK (ref_type IN ('article','paper','book','video','podcast',
                            'tweet','repo','conversation','internal','other'));

-- 3. Remove relation='attachment' hack from cell_references
-- Clean up any V0023 attachment-hack rows before tightening constraints
DELETE FROM cell_references WHERE relation = 'attachment';

ALTER TABLE cell_references
    DROP CONSTRAINT IF EXISTS cell_references_relation_check;
ALTER TABLE cell_references
    ADD CONSTRAINT cell_references_relation_check
        CHECK (relation IN ('related_to','builds_on','contradicts',
                            'refines','source','inspired_by','extends'));

-- 4. Direct cell ↔ attachment join table
-- ON DELETE RESTRICT (default): both cells and attachments use soft-delete (deleted_at / valid_until).
-- Hard-deletes must remove cell_attachments rows first.
CREATE TABLE cell_attachments (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    cell_id           UUID        NOT NULL REFERENCES cells(id),
    attachment_id     UUID        NOT NULL REFERENCES attachments(id),
    extraction_source BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (cell_id, attachment_id)
);

CREATE INDEX cell_attachments_attachment_id_idx ON cell_attachments(attachment_id);
