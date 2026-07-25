package com.hivemem.attachment;

import com.hivemem.llm.LlmCallCost;
import com.hivemem.llm.LlmCostPolicy;
import org.jooq.DSLContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Daily-cost-cap tracker for Vision-API calls. Mirrors SummarizeBudgetTracker. What a call
 * costs is decided by {@link LlmCostPolicy}, from the cost Vistierie reports for the call it
 * actually routed.
 *
 * <p>NOTE ON UNITS: every amount here is EUR. The column {@code total_cost_usd} and the
 * property {@code vision-daily-budget-usd} keep their names for historical reasons — renaming
 * them would be a migration and a breaking config change; see Decision 2 of the design spec.
 */
public class VisionBudgetTracker {

    /**
     * Coarse per-call reservation held while a vision call is in flight, so concurrent callers
     * cannot all pass {@link #canSpend()} before any cost has actually been recorded
     * (check-then-act overshoot). Mirrors SummarizeBudgetTracker.
     *
     * <p>The unit is EUR, like everything else in this class. This is <em>not</em> a price and
     * is not derived from {@link LlmCostPolicy}: it is a placeholder stand-in for a cost that
     * is only known after the call returns.
     *
     * <p>LIMITATION: the value assumes a haiku-class model. Vistierie chooses the model, so if
     * it routes to an opus-class model an actual call can cost considerably more than this
     * reservation — in that case the in-flight reservation under-reserves by the same factor
     * and the daily cap can be overshot correspondingly under concurrency. Committed spend is
     * unaffected: {@link #recordCall} always books the real reported cost.
     */
    private static final double EST_CALL_COST_EUR = 0.02;

    private final LlmCostPolicy policy = new LlmCostPolicy();
    private final DSLContext dsl;
    private final double dailyBudgetUsd;
    private final AtomicInteger inFlightCalls = new AtomicInteger();

    public VisionBudgetTracker(DSLContext dsl, double dailyBudgetUsd) {
        this.dsl = dsl;
        this.dailyBudgetUsd = dailyBudgetUsd;
    }

    public boolean canSpend() {
        if (dailyBudgetUsd <= 0) return false;
        double reserved = inFlightCalls.get() * EST_CALL_COST_EUR;
        var rec = dsl.fetchOptional(
                "SELECT total_cost_usd FROM vision_usage WHERE day = ?", today());
        if (rec.isEmpty()) return reserved < dailyBudgetUsd;
        BigDecimal spent = rec.get().get(0, BigDecimal.class);
        return spent == null || spent.doubleValue() + reserved < dailyBudgetUsd;
    }

    /** Mark a vision call as in flight; MUST be paired with {@link #endCall()} in a finally. */
    public void beginCall() {
        inFlightCalls.incrementAndGet();
    }

    /** Release the in-flight reservation taken by {@link #beginCall()}. */
    public void endCall() {
        inFlightCalls.decrementAndGet();
    }

    /**
     * Books one vision call. Returns the amount booked, in EUR, so the caller can log exactly
     * what was charged instead of recomputing it.
     *
     * <p>NOTE ON UNITS: the column is named {@code total_cost_usd} for historical reasons but
     * holds EUR — Vistierie prices in EUR-micros and HiveMem books that unit unchanged. The
     * configured {@code vision-daily-budget-usd} is therefore a EUR budget. Renaming either
     * would be a migration / a breaking config change; see Decision 2 of the design spec.
     */
    public BigDecimal recordCall(LlmCallCost call) {
        BigDecimal cost = policy.eurFor(call);
        int inputTokens = call.totalInputTokens();
        dsl.execute(
                "INSERT INTO vision_usage (day, total_calls, total_input_tokens, total_output_tokens, total_cost_usd) "
                        + "VALUES (?, 1, ?, ?, ?) "
                        + "ON CONFLICT (day) DO UPDATE SET "
                        + "  total_calls = vision_usage.total_calls + 1, "
                        + "  total_input_tokens = vision_usage.total_input_tokens + EXCLUDED.total_input_tokens, "
                        + "  total_output_tokens = vision_usage.total_output_tokens + EXCLUDED.total_output_tokens, "
                        + "  total_cost_usd = vision_usage.total_cost_usd + EXCLUDED.total_cost_usd",
                today(), inputTokens, call.outputTokens(), cost);
        return cost;
    }

    /** UTC day boundary — consistent with SummarizeBudgetTracker, regardless of server-local TZ. */
    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }
}
