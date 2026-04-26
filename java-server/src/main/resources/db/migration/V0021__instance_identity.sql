-- V0019: instance_identity — singleton row holding this hive's UUID.
--
-- Stamped on every ops_log entry so peers can attribute ops. Created on
-- first boot via InstanceConfig if the table is empty. Idempotent.

CREATE TABLE IF NOT EXISTS instance_identity (
    id          INTEGER PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    instance_id UUID NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
