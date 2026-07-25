package com.hivemem.llm;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class LlmCallCostTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode fixture(String name) throws Exception {
        try (InputStream in = LlmCallCostTest.class.getResourceAsStream("/fixtures/" + name)) {
            return MAPPER.readTree(in);
        }
    }

    // The literal numbers below are transcribed from the captured envelopes in
    // src/test/resources/fixtures. They are asserted literally on purpose: a relative
    // assertion (e.g. total == sum of parts) holds for ANY implementation, including one
    // that mis-spells every key and parses the whole envelope as zeros. Renaming a key in
    // LlmCallCost.from() must break these tests.

    @Test
    void readsAllFourTokenFieldsFromTheRealCompleteEnvelope() throws Exception {
        LlmCallCost c = LlmCallCost.from(fixture("vistierie-complete-response.json"));

        assertThat(c.provider()).isEqualTo("claude-subscription");
        // Vistierie routed to sonnet although the captured call asked for haiku.
        assertThat(c.model()).isEqualTo("claude-sonnet-5");
        assertThat(c.inputTokens()).isEqualTo(2);
        assertThat(c.outputTokens()).isEqualTo(4);
        assertThat(c.cacheCreationTokens()).isEqualTo(23097);
        assertThat(c.cacheReadTokens()).isEqualTo(0);
        assertThat(c.costMicros()).isEqualTo(0L);

        // The whole point: cache tokens are input too. A parser that reads only
        // usage.inputTokens under-counts by orders of magnitude when caching is on.
        assertThat(c.totalInputTokens()).isEqualTo(23099);
        assertThat(c.totalInputTokens())
                .isEqualTo(c.inputTokens() + c.cacheCreationTokens() + c.cacheReadTokens());
    }

    @Test
    void readsTheVisionEnvelopeToo() throws Exception {
        JsonNode node = fixture("vistierie-vision-response.json");
        LlmCallCost c = LlmCallCost.from(node);

        assertThat(c.provider()).isEqualTo("claude-subscription");
        assertThat(c.model()).isEqualTo("claude-sonnet-5");
        assertThat(c.inputTokens()).isEqualTo(2);
        assertThat(c.outputTokens()).isEqualTo(5);
        assertThat(c.cacheCreationTokens()).isEqualTo(3261);
        assertThat(c.cacheReadTokens()).isEqualTo(19834);
        assertThat(c.costMicros()).isEqualTo(0L);
        assertThat(c.totalInputTokens()).isEqualTo(23097);

        // Both captured calls were subscription-routed, so cost_micros reads 0 and no value
        // assertion can pin its spelling. Pin the key on the fixture side instead, so a
        // silently re-spelled envelope is still caught here.
        assertThat(node.has("cost_micros")).isTrue();
    }

    @Test
    void sumsAllThreeInputKinds() {
        LlmCallCost c = new LlmCallCost("bedrock", "claude-haiku-4-5", 2, 1487, 25681, 0, 0L);
        assertThat(c.totalInputTokens()).isEqualTo(25683);
    }

    @Test
    void nullNodeYieldsZeroSentinel() {
        assertThat(LlmCallCost.from(null)).isSameAs(LlmCallCost.ZERO);
    }

    @Test
    void absentFieldsReadAsZeroAndNull() throws Exception {
        JsonNode node = MAPPER.readTree("{\"text\":\"hi\"}");
        LlmCallCost c = LlmCallCost.from(node);
        assertThat(c.inputTokens()).isZero();
        assertThat(c.outputTokens()).isZero();
        assertThat(c.cacheCreationTokens()).isZero();
        assertThat(c.cacheReadTokens()).isZero();
        assertThat(c.costMicros()).isZero();
        assertThat(c.provider()).isNull();
        assertThat(c.model()).isNull();
    }

    @Test
    void malformedFieldTypesDoNotThrow() throws Exception {
        JsonNode node = MAPPER.readTree(
                "{\"usage\":{\"inputTokens\":\"abc\"},\"cost_micros\":\"nope\",\"provider\":123}");
        assertThatCode(() -> LlmCallCost.from(node)).doesNotThrowAnyException();
        assertThat(LlmCallCost.from(node).inputTokens()).isZero();
        assertThat(LlmCallCost.from(node).costMicros()).isZero();
    }
}
