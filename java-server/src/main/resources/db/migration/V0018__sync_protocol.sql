-- V0016: bidirectional sync protocol — append-only operation log + peer state.
--
-- Why
-- ---
-- Multiple HiveMem instances need to converge to the same knowledge state.
-- Instead of replicating rows, every write produces an immutable op_log entry.
-- Peers pull ops they haven't seen and replay them idempotently, so two hives
-- that pull from each other end up with identical content. Conflict resolution
-- is itself just another op — a resolved merge on instance A propagates to
-- instance B on the next pull, so a conflict only ever has to be solved once.
--
-- Tables
-- ------
--   ops_log         immutable record of every state-changing operation
--   sync_peers      remote instances we pull from, with last-seen watermark
--   applied_ops     ops already replayed locally (idempotency guard)
--   sync_conflicts  competing ops that need human/LLM resolution

CREATE TABLE IF NOT EXISTS ops_log (
    seq         BIGSERIAL PRIMARY KEY,
    instance_id UUID        NOT NULL,
    op_id       UUID        NOT NULL UNIQUE,
    op_type     TEXT        NOT NULL,
    payload     JSONB       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ops_log_instance_seq
    ON ops_log (instance_id, seq);

CREATE TABLE IF NOT EXISTS sync_peers (
    peer_uuid      UUID        PRIMARY KEY,
    peer_url       TEXT        NOT NULL,
    last_seen_seq  BIGINT      NOT NULL DEFAULT 0,
    last_synced_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS applied_ops (
    op_id      UUID        PRIMARY KEY,
    source_peer UUID,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS sync_conflicts (
    id               UUID        PRIMARY KEY,
    cell_id          UUID,
    competing_op_a   UUID        NOT NULL,
    competing_op_b   UUID        NOT NULL,
    detected_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at      TIMESTAMPTZ,
    resolution_op_id UUID
);

CREATE INDEX IF NOT EXISTS idx_sync_conflicts_unresolved
    ON sync_conflicts (detected_at)
    WHERE resolved_at IS NULL;
