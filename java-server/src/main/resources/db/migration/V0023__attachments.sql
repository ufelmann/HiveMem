CREATE TABLE attachments (
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    file_hash            TEXT        NOT NULL UNIQUE,
    mime_type            TEXT        NOT NULL,
    original_filename    TEXT        NOT NULL,
    size_bytes           BIGINT      NOT NULL,
    s3_key_original      TEXT        NOT NULL,
    s3_key_thumbnail     TEXT,
    extracted_text       TEXT,
    uploaded_by          TEXT        NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at           TIMESTAMPTZ
);

-- Extend references_.ref_type check to include 'attachment'
ALTER TABLE references_
    DROP CONSTRAINT IF EXISTS references__ref_type_check;
ALTER TABLE references_
    ADD CONSTRAINT references__ref_type_check
        CHECK (ref_type IN ('article','paper','book','video','podcast','tweet','repo','conversation','internal','other','attachment'));

-- Extend cell_references.relation check to include 'attachment'
ALTER TABLE cell_references
    DROP CONSTRAINT IF EXISTS drawer_references_relation_check;
ALTER TABLE cell_references
    DROP CONSTRAINT IF EXISTS cell_references_relation_check;
ALTER TABLE cell_references
    ADD CONSTRAINT cell_references_relation_check
        CHECK (relation IN ('related_to','builds_on','contradicts','refines','source','inspired_by','extends','attachment'));
