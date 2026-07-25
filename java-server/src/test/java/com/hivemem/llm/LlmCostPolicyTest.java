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
        BigDecimal eur = policy.eurFor(call("bedrock", "claude-haiku-4-5", 1L));
        assertThat(eur).isEqualByComparingTo(new BigDecimal("0.000001"));
        // isEqualByComparingTo ignores scale, so pin the scale the method name promises.
        assertThat(eur.scale()).isEqualTo(6);
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
    void componentsAreSummedAtFullPrecisionAndRoundedOnce() {
        // 10 in * 0.92/1M = 0.0000092 ; 2 cache-creation * 1.15/1M = 0.0000023.
        // Summed first: 0.0000115 -> HALF_UP at scale 6 -> 0.000012.
        // Rounded per component first: 0.000009 + 0.000002 -> 0.000011. The value below is
        // the one that distinguishes the two; the scale assertion alone would not.
        LlmCallCost c = new LlmCallCost("bedrock", "claude-haiku-4-5", 10, 0, 2, 0, 0L);
        BigDecimal eur = policy.eurFor(c);
        assertThat(eur).isEqualByComparingTo(new BigDecimal("0.000012"));
        assertThat(eur.scale()).isEqualTo(6);
    }

    @Test
    void aSuccessorVersionDoesNotInheritItsPredecessorsCheaperRow() {
        // "claude-opus-5-1-..." is a NEW model, not a dated build of "claude-opus-5".
        // Booking the opus-5 row here would under-charge 3x; fail-safe means UNKNOWN.
        LlmCallCost c = new LlmCallCost(
                "bedrock", "claude-opus-5-1-20261101", 1_000_000, 0, 0, 0, 0L);
        assertThat(policy.eurFor(c)).isEqualByComparingTo(new BigDecimal("13.800000"));
    }

    @Test
    void resolvesRegionalInferenceProfilePrefixes() {
        // us./apac. profiles are the same model — charging them the UNKNOWN ceiling would
        // over-charge 15x and exhaust the daily budget on work that was cheap.
        for (String id : new String[]{
                "us.anthropic.claude-haiku-4-5-20251001-v1:0",
                "apac.anthropic.claude-haiku-4-5-20251001-v1:0",
                "global.anthropic.claude-haiku-4-5-20251001-v1:0",
                "anthropic.claude-haiku-4-5-20251001-v1:0"}) {
            LlmCallCost c = new LlmCallCost("bedrock", id, 1_000_000, 0, 0, 0, 0L);
            assertThat(policy.eurFor(c))
                    .describedAs(id)
                    .isEqualByComparingTo(new BigDecimal("0.920000"));
        }
    }

    @Test
    void negativeTokenCountsNeverProduceACredit() {
        // A negative usage field must not subtract from the day's spend.
        LlmCallCost c = new LlmCallCost("bedrock", "claude-haiku-4-5", -1000, -1000, -1000, -1000, 0L);
        assertThat(policy.eurFor(c)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void negativeCostMicrosIsNotBookedAsACredit(CapturedOutput out) {
        // Deliberate: only a POSITIVE cost_micros is trusted as the cost of record. A negative
        // one would credit the daily budget and defeat the spend gate, so it falls through to
        // the estimate path and stays loud.
        LlmCallCost c = new LlmCallCost("bedrock", "claude-haiku-4-5", 100, 50, 0, 0, -5950L);
        assertThat(policy.eurFor(c)).isEqualByComparingTo(new BigDecimal("0.000322"));
        assertThat(out).contains("WARN").contains("bedrock");
    }
}
