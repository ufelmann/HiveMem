package com.hivemem.embedding;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@link EmbeddingClient} interface's default methods directly, independent of any
 * concrete implementation. {@code maxChars()} defaulting to {@code CONTENT_EMBED_MAX_CHARS} is
 * currently exercised only incidentally by other tests; the next task routes
 * {@code encodeForCell} through it, so a silent regression here (e.g. a stray {@code return 0;})
 * would misroute every cell without a summary to the "needs_summary" fallback.
 */
class EmbeddingClientDefaultMethodsTest {

    @Test
    void maxCharsDefaultsToTheHistoricalOnnxLimit() {
        EmbeddingClient client = new EmbeddingClient() {
            @Override
            public List<Float> encodeDocument(String text) {
                throw new UnsupportedOperationException("not needed for this test");
            }

            @Override
            public EmbeddingInfo getInfo() {
                throw new UnsupportedOperationException("not needed for this test");
            }
        };

        assertThat(client.maxChars()).isEqualTo(EmbeddingClient.CONTENT_EMBED_MAX_CHARS);
    }
}
