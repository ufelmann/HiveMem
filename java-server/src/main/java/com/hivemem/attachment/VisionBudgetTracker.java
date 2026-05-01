package com.hivemem.attachment;

import org.jooq.DSLContext;

import java.time.LocalDate;

/** Daily-cost-cap tracker for Vision-API calls. Mirrors SummarizeBudgetTracker. */
public class VisionBudgetTracker {

    // Claude Haiku 4.5 pricing (USD per 1M tokens). Update when pricing changes.
    private static final double INPUT_USD_PER_M  = 1.0;
    private static final double OUTPUT_USD_PER_M = 5.0;

    private final DSLContext dsl;
    private final double dailyBudgetUsd;

    public VisionBudgetTracker(DSLContext dsl, double dailyBudgetUsd) {
        this.dsl = dsl;
        this.dailyBudgetUsd = dailyBudgetUsd;
    }

    public boolean canSpend() {
        if (dailyBudgetUsd <= 0) return false;
        var rec = dsl.fetchOptional(
                "SELECT total_cost_usd FROM vision_usage WHERE day = ?", LocalDate.now());
        if (rec.isEmpty()) return true;
        java.math.BigDecimal spent = rec.get().get(0, java.math.BigDecimal.class);
        return spent == null || spent.doubleValue() < dailyBudgetUsd;
    }

    public void recordCall(int inputTokens, int outputTokens) {
        double cost = (inputTokens / 1_000_000.0) * INPUT_USD_PER_M
                + (outputTokens / 1_000_000.0) * OUTPUT_USD_PER_M;
        dsl.execute(
                "INSERT INTO vision_usage (day, total_calls, total_input_tokens, total_output_tokens, total_cost_usd) "
                        + "VALUES (?, 1, ?, ?, ?) "
                        + "ON CONFLICT (day) DO UPDATE SET "
                        + "  total_calls = vision_usage.total_calls + 1, "
                        + "  total_input_tokens = vision_usage.total_input_tokens + EXCLUDED.total_input_tokens, "
                        + "  total_output_tokens = vision_usage.total_output_tokens + EXCLUDED.total_output_tokens, "
                        + "  total_cost_usd = vision_usage.total_cost_usd + EXCLUDED.total_cost_usd",
                LocalDate.now(), inputTokens, outputTokens, cost);
    }
}
