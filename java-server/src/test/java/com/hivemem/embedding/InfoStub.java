package com.hivemem.embedding;

/**
 * Builds {@code /info} response JSON for tests. Centralizes the snake_case {@code max_chars}
 * key so every test double for {@link HttpEmbeddingClient#getInfo()} stays in sync with the
 * sidecar contract (see Task 8, which makes the real sidecar emit this field). Reused by
 * Tasks 5 and 7's {@code EmbeddingClient} work — update here, not per-test, if the payload
 * shape changes again.
 */
final class InfoStub {

    private InfoStub() {
    }

    /** A well-formed /info payload: model, dimension and a positive max_chars. */
    static String json(String model, int dimension, int maxChars) {
        return "{\"model\":\"" + model + "\",\"dimension\":" + dimension
                + ",\"max_chars\":" + maxChars + "}";
    }
}
