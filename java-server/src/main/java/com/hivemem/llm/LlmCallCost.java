package com.hivemem.llm;

import tools.jackson.databind.JsonNode;

/**
 * One Vistierie /llm/complete or /llm/vision call's cost facts, as reported by the layer that
 * actually routed it. HiveMem reads the model and price from here rather than reconstructing
 * them from its own request: Vistierie may route to a different model than was asked for.
 *
 * <p>{@code costMicros} is in Vistierie's unit, EUR-micros (1 EUR = 1_000_000) — see
 * {@code PriceTable} in Vistierie and Decision 2 of the design spec.
 */
public record LlmCallCost(String provider, String model,
                          int inputTokens, int outputTokens,
                          int cacheCreationTokens, int cacheReadTokens,
                          long costMicros) {

    /** No call, no cost — used for the null-body paths. Identity-comparable on purpose. */
    public static final LlmCallCost ZERO = new LlmCallCost(null, null, 0, 0, 0, 0, 0L);

    /**
     * Every input token the provider billed. Anthropic reports only the UNCACHED tokens in
     * {@code inputTokens}; with prompt caching on, the bulk of the real input sits in the two
     * cache fields. Reading only {@code inputTokens} under-counts by orders of magnitude.
     */
    public int totalInputTokens() {
        return inputTokens + cacheCreationTokens + cacheReadTokens;
    }

    /**
     * Never throws: a null node, absent fields, or wrongly-typed fields all read as 0/null.
     * Cost accounting must never break the functional path.
     *
     * <p>Field names are Vistierie's, and they are mixed-case: the {@code usage} object
     * serializes its Java record components (camelCase), while {@code cost_micros} is itself a
     * snake_case component name. Anthropic's raw {@code cache_creation_input_tokens} spelling
     * never reaches HiveMem.
     */
    public static LlmCallCost from(JsonNode resp) {
        if (resp == null) return ZERO;
        JsonNode usage = resp.path("usage");
        return new LlmCallCost(
                textOrNull(resp, "provider"),
                textOrNull(resp, "model"),
                usage.path("inputTokens").asInt(0),
                usage.path("outputTokens").asInt(0),
                usage.path("cacheCreationInputTokens").asInt(0),
                usage.path("cacheReadInputTokens").asInt(0),
                resp.path("cost_micros").asLong(0L));
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isTextual() ? n.asText() : null;
    }
}
