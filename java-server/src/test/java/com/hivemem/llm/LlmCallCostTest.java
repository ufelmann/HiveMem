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

    @Test
    void readsAllFourTokenFieldsFromTheRealCompleteEnvelope() throws Exception {
        LlmCallCost c = LlmCallCost.from(fixture("vistierie-complete-response.json"));

        // The whole point: cache tokens are input too. A parser that reads only
        // usage.inputTokens under-counts by orders of magnitude when caching is on.
        assertThat(c.totalInputTokens())
                .isEqualTo(c.inputTokens() + c.cacheCreationTokens() + c.cacheReadTokens());
        assertThat(c.provider()).isNotBlank();
        assertThat(c.model()).isNotBlank();
    }

    @Test
    void readsTheVisionEnvelopeToo() throws Exception {
        LlmCallCost c = LlmCallCost.from(fixture("vistierie-vision-response.json"));
        assertThat(c.provider()).isNotBlank();
        assertThat(c.model()).isNotBlank();
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
