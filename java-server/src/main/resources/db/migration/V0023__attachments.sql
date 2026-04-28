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
