-- V0052: Automatic contradiction detection.
--
-- Three tables:
--   contradiction_jobs    — one row per dispatched Vistierie run (both stages)
--   predicate_cardinality — Stage-A cache: is a predicate single- or multi-valued
--   fact_contradictions   — Stage-B pair rows, reserved at dispatch
--
-- contradiction_jobs.status deliberately carries NO CHECK constraint, mirroring
-- V0029__consumption_jobs.sql: the claim() pattern writes 'processing', which an
-- awaiting|done|failed CHECK would reject on every claim.

CREATE TABLE contradiction_jobs (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    correlation_id   UUID NOT NULL UNIQUE,
    vistierie_run_id TEXT,
    kind             TEXT NOT NULL CHECK (kind IN ('pairs','cardinality')),
    item_count       INTEGER NOT NULL,
    status           TEXT NOT NULL DEFAULT 'awaiting',  -- awaiting | processing | done | failed
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_contradiction_jobs_status ON contradiction_jobs (status, updated_at);
CREATE INDEX idx_contradiction_jobs_run_id ON contradiction_jobs (vistierie_run_id);
-- read by the daily run ceiling (countToday)
CREATE INDEX idx_contradiction_jobs_created ON contradiction_jobs (created_at);

-- cardinality is NULL between reservation and verdict; that row is what stops a
-- predicate the judge never answers from being re-asked on every tick.
CREATE TABLE predicate_cardinality (
    predicate    TEXT PRIMARY KEY,
    cardinality  TEXT CHECK (cardinality IN ('single_valued','multi_valued')),
    status       TEXT NOT NULL DEFAULT 'in_flight'
                 CHECK (status IN ('in_flight','retryable','decided','deferred')),
    attempts     INTEGER NOT NULL DEFAULT 1,
    job_id       UUID REFERENCES contradiction_jobs(id),
    rationale    TEXT,
    confidence   REAL,
    decided_by   TEXT CHECK (decided_by IN ('judge','human')),
    decided_at   TIMESTAMPTZ
);

CREATE INDEX idx_predicate_cardinality_job ON predicate_cardinality (job_id);

CREATE TABLE fact_contradictions (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    fact_a           UUID NOT NULL REFERENCES facts(id),
    fact_b           UUID NOT NULL REFERENCES facts(id),
    subject          TEXT NOT NULL,
    predicate        TEXT NOT NULL,
    rationale        TEXT,
    judge_confidence REAL,
    suggested_keep   UUID REFERENCES facts(id),
    job_id           UUID REFERENCES contradiction_jobs(id),
    -- in_flight -> pending (judge confirmed) | not_contradictory (cleared) | retryable (job failed)
    -- retryable -> in_flight (re-reserved) | deferred (max attempts);  deferred -> retryable (human requeue)
    -- pending -> resolved (human picked a winner) | dismissed (multi-valued);  in_flight/retryable -> superseded (cleanup)
    status           TEXT NOT NULL DEFAULT 'in_flight'
                     CHECK (status IN ('in_flight','retryable','pending','resolved',
                                       'dismissed','superseded','not_contradictory',
                                       'deferred')),
    attempts         INTEGER NOT NULL DEFAULT 1,
    detected_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at      TIMESTAMPTZ,
    -- A fact cannot contradict itself; the pair index collapses (A,B)/(B,A) but would accept (A,A).
    CONSTRAINT ck_fact_contradictions_distinct CHECK (fact_a <> fact_b)
);

-- Order-independent: (A,B) and (B,A) are the same pair.
CREATE UNIQUE INDEX ux_fact_contradictions_pair
    ON fact_contradictions (LEAST(fact_a, fact_b), GREATEST(fact_a, fact_b));

CREATE INDEX idx_fact_contradictions_status ON fact_contradictions (status, detected_at);
CREATE INDEX idx_fact_contradictions_job ON fact_contradictions (job_id);
CREATE INDEX idx_fact_contradictions_predicate ON fact_contradictions (predicate, status);

-- No index on facts(subject, predicate): V0006__schema_parity.sql:33 already has
-- idx_facts_subj_pred on exactly those columns.
