package com.hivemem.summarize;

/**
 * Stateless utility deciding whether a cell needs a generated summary so that its
 * embedding (which uses the summary when present) can carry full meaning.
 *
 * <p>Implemented as static methods rather than a Spring bean so callers can use it
 * from any Spring context without forcing every narrow {@code @Import}-based test
 * configuration to register a bean for it.
 */
public final class NeedsSummaryDecider {

    /**
     * Default threshold in characters. Roughly matches the embedding model's ~128-token
     * input window for multilingual MiniLM. Cells longer than this need a summary so
     * the embedding represents the cell's meaning, not its first sentence.
     */
    public static final int DEFAULT_THRESHOLD_CHARS = 500;

    private NeedsSummaryDecider() {}

    public static boolean needsSummary(String content, String summary) {
        return needsSummary(content, summary, DEFAULT_THRESHOLD_CHARS);
    }

    public static boolean needsSummary(String content, String summary, int thresholdChars) {
        if (summary != null && !summary.isBlank()) return false;
        if (content == null) return false;
        return content.length() > thresholdChars;
    }
}
