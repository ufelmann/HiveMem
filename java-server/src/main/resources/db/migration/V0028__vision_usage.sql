-- V0028: Daily cost-cap-Tracker for the Vision-API-Calls.
-- Identical schema to summarize_usage, separate table for separate budget.

CREATE TABLE vision_usage (
    day                 DATE         PRIMARY KEY,
    total_calls         INTEGER      NOT NULL DEFAULT 0,
    total_input_tokens  INTEGER      NOT NULL DEFAULT 0,
    total_output_tokens INTEGER      NOT NULL DEFAULT 0,
    total_cost_usd      NUMERIC(10,6) NOT NULL DEFAULT 0
);
