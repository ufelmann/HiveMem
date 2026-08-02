package com.hivemem.embedding;

import java.util.List;

public interface EmbeddingClient {

    /**
     * Fallback char cap used only when a backend's {@code /info} advertises no
     * {@code max_chars} of its own; see {@link #maxChars()}. Value matches the
     * ONNX backend's calibrated MiniLM limit (~128 tokens ≈ 500 chars multilingual).
     */
    int CONTENT_EMBED_MAX_CHARS = 500;

    List<Float> encodeDocument(String text);

    default List<Float> encodeQuery(String text) {
        return encodeDocument(text);
    }

    /**
     * Content-first embedding for cells:
     * <ul>
     *   <li>content non-blank and ≤ {@link #maxChars()} → embed the content</li>
     *   <li>content blank, absent, or too long but a summary exists → embed the summary</li>
     *   <li>neither → {@code null}; the caller tags {@code needs_summary}</li>
     * </ul>
     * The cap is backend-dependent (500 on ONNX, 8000 on Ollama), so it comes from
     * {@link #maxChars()}, not from the constant. Blank content (e.g. a legacy row with
     * {@code content = ''}) must not win over a real summary — embedding an empty string
     * produces a meaningless vector.
     */
    default List<Float> encodeForCell(String content, String summary) {
        if (content != null && !content.isBlank() && content.length() <= maxChars()) {
            return encodeDocument(content);
        }
        if (summary != null && !summary.isBlank()) {
            return encodeDocument(summary);
        }
        return null;
    }

    EmbeddingInfo getInfo();

    /**
     * The embedding dimension. Implementations may serve this from a local cache (see
     * {@link HttpEmbeddingClient}) so per-request callers (search_kg, entity_overview, …)
     * don't pay an HTTP hop; the default resolves it via {@link #getInfo()}.
     */
    default int dimension() {
        return getInfo().dimension();
    }

    /** Drop any cached vectors/model info (e.g. after an embedding-model migration). */
    default void invalidateCaches() {
    }

    /** Longest content this backend can embed meaningfully, from /info.
     *  The default is the historical ONNX value; real implementors override it.
     *  (Mockito mocks return 0 regardless of this default — they are safe only
     *  because every mock-based test stubs encodeForCell directly.) */
    default int maxChars() {
        return CONTENT_EMBED_MAX_CHARS;
    }
}
