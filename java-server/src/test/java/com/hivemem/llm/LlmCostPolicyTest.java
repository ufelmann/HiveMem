package com.hivemem.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class LlmCostPolicyTest {

    private final LlmCostPolicy policy = new LlmCostPolicy();

    private static LlmCallCost call(String provider, String model, long micros) {
        return new LlmCallCost(provider, model, 100, 50, 0, 0, micros);
    }

    @Test
    void passesVistierieCostThroughExactly() {
        assertThat(policy.eurFor(call("bedrock", "claude-haiku-4-5", 5950L)))
                .isEqualByComparingTo(new BigDecimal("0.005950"));
    }

    @Test
    void passthroughKeepsMicroScale() {
        // 1 micro must survive as 0.000001, not round to zero.
        assertThat(policy.eurFor(call("bedrock", "claude-haiku-4-5", 1L)))
                .isEqualByComparingTo(new BigDecimal("0.000001"));
    }

    @Test
    void subscriptionCallsAreFreeAndSilent(CapturedOutput out) {
        assertThat(policy.eurFor(call("claude-subscription", "claude-sonnet-5", 0L)))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(out).doesNotContain("WARN");
    }

    @Test
    void zeroCostFromAnotherProviderIsEstimatedAndWarned(CapturedOutput out) {
        BigDecimal eur = policy.eurFor(call("bedrock", "claude-haiku-4-5", 0L));
        // 100 in * 0.92/1M + 50 out * 4.60/1M = 0.000092 + 0.000230
        assertThat(eur).isEqualByComparingTo(new BigDecimal("0.000322"));
        assertThat(out).contains("bedrock").contains("claude-haiku-4-5");
    }

    @Test
    void nullProviderCountsAsUnknownNotSubscription(CapturedOutput out) {
        assertThat(policy.eurFor(call(null, "claude-haiku-4-5", 0L)))
                .isGreaterThan(BigDecimal.ZERO);
        assertThat(out).contains("unknown");
    }

    @Test
    void zeroSentinelBooksNothingAndStaysQuiet(CapturedOutput out) {
        assertThat(policy.eurFor(LlmCallCost.ZERO)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(out).doesNotContain("WARN");
    }

    @Test
    void parsedAllZeroCallStillWarns(CapturedOutput out) {
        // NOT the sentinel: a real envelope whose token fields all read zero is the
        // signature of a renamed usage field — it must stay loud.
        LlmCallCost parsed = new LlmCallCost("bedrock", "claude-haiku-4-5", 0, 0, 0, 0, 0L);
        policy.eurFor(parsed);
        assertThat(out).contains("bedrock");
    }

    @Test
    void resolvesBedrockInferenceProfileIds() {
        // The id shape actually present in vistierie.llm_calls.
        LlmCallCost c = new LlmCallCost(
                "bedrock", "eu.anthropic.claude-haiku-4-5-20251001-v1:0", 1_000_000, 0, 0, 0, 0L);
        assertThat(policy.eurFor(c)).isEqualByComparingTo(new BigDecimal("0.920000"));
    }

    @Test
    void resolvesDatedFirstPartyIds() {
        LlmCallCost c = new LlmCallCost(
                "bedrock", "claude-haiku-4-5-20261001", 1_000_000, 0, 0, 0, 0L);
        assertThat(policy.eurFor(c)).isEqualByComparingTo(new BigDecimal("0.920000"));
    }

    @Test
    void unknownModelFallsToTheMostExpensiveRow() {
        LlmCallCost c = new LlmCallCost("bedrock", "some-new-model", 1_000_000, 0, 0, 0, 0L);
        assertThat(policy.eurFor(c)).isEqualByComparingTo(new BigDecimal("13.800000"));
    }

    @Test
    void pricesCacheTokensAtWriteAndReadMultipliers() {
        // 1M cache-creation @ 0.92*1.25 = 1.15 ; 1M cache-read @ 0.92*0.1 = 0.092
        LlmCallCost c = new LlmCallCost(
                "bedrock", "claude-haiku-4-5", 0, 0, 1_000_000, 1_000_000, 0L);
        assertThat(policy.eurFor(c)).isEqualByComparingTo(new BigDecimal("1.242000"));
    }

    @Test
    void aSingleCacheReadTokenDoesNotRoundAwayBeforeTheFinalRounding() {
        // 1 token * 0.092/1M = 9.2e-8 -> rounds to 0.000000 at scale 6, but must not be
        // rounded to zero per-component before the sum.
        LlmCallCost c = new LlmCallCost("bedrock", "claude-haiku-4-5", 0, 0, 0, 1, 0L);
        assertThat(policy.eurFor(c).scale()).isEqualTo(6);
    }
}
