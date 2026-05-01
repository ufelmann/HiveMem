-- V0026: Daily usage tracker for the auto-summarizer (cost cap).

CREATE TABLE IF NOT EXISTS summarize_usage (
    day                  DATE         PRIMARY KEY,
    total_calls          INTEGER      NOT NULL DEFAULT 0,
    total_input_tokens   INTEGER      NOT NULL DEFAULT 0,
    total_output_tokens  INTEGER      NOT NULL DEFAULT 0,
    total_cost_usd       NUMERIC(10,6) NOT NULL DEFAULT 0
);

COMMENT ON TABLE summarize_usage IS
    'One row per UTC day. SummarizeBudgetTracker upserts after each Anthropic call.';
