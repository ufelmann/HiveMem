package com.hivemem.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a cell's content into page-aware passages for embedding. Pure function, no dependencies —
 * fully unit-testable and callable from a background sweep. See design §3.3 for the algorithm this
 * follows step by step; do not improvise around it.
 *
 * <p>Algorithm (design §3.3, points 1-6):
 * <ol>
 *   <li>Split content at {@code \[page=(\d+)\]} markers into page pieces; the marker stays at the
 *       start of its piece. A marker in any other shape counts as no marker at all.</li>
 *   <li>Content without a recognized marker is a single unpaginated piece.</li>
 *   <li>Consecutive pieces are packed greedily while the running sum stays within
 *       {@link ChunkProperties#getTargetChars()}. {@code pageFrom}/{@code pageTo} span the packed
 *       pieces.</li>
 *   <li>A single piece above {@link ChunkProperties#getMaxChars()} is split further: first at blank
 *       lines, else at sentence ends ({@code ". "}, {@code "! "}, {@code "? "}), else hard at the
 *       character limit. Every sub-piece keeps its source piece's page number.</li>
 *   <li>{@code ordinal} runs from 0 in reading order.</li>
 *   <li>If the run produces exactly one chunk — which, by construction, covers the entire content —
 *       nothing is returned: its vector would be bit-identical to the cell vector, so it adds
 *       embedding cost and scan overhead for zero information gain.</li>
 * </ol>
 */
public final class CellChunker {

    private static final Pattern PAGE_MARKER = Pattern.compile("\\[page=(\\d+)\\]");
    private static final Pattern BLANK_LINE = Pattern.compile("\\n[ \\t]*\\n");
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?] ");

    private final ChunkProperties props;

    public CellChunker(ChunkProperties props) {
        this.props = props;
    }

    /**
     * @param content the cell's full content
     * @return chunks in reading order, or an empty list when the content yields a single chunk
     *     covering everything (design §3.3 rule 6)
     */
    public List<Chunk> chunk(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }

        List<Piece> pieces = splitIntoPagePieces(content);
        List<RawChunk> packed = packPieces(pieces);

        List<RawChunk> finalChunks = new ArrayList<>();
        for (RawChunk rc : packed) {
            if (rc.text().length() > props.getMaxChars()) {
                finalChunks.addAll(splitOversized(rc));
            } else {
                finalChunks.add(rc);
            }
        }

        if (finalChunks.size() <= 1) {
            // A single resulting chunk necessarily covers the whole content (the packing/splitting
            // step is a partition of the input) — rule 6 suppresses it.
            return List.of();
        }

        List<Chunk> result = new ArrayList<>(finalChunks.size());
        int ordinal = 0;
        for (RawChunk rc : finalChunks) {
            result.add(new Chunk(ordinal++, rc.pageFrom(), rc.pageTo(), rc.text()));
        }
        return result;
    }

    /** Splits content at page markers; the marker text stays at the start of its piece. */
    private List<Piece> splitIntoPagePieces(String content) {
        Matcher m = PAGE_MARKER.matcher(content);
        List<Integer> starts = new ArrayList<>();
        List<Integer> pages = new ArrayList<>();
        while (m.find()) {
            starts.add(m.start());
            pages.add(Integer.parseInt(m.group(1)));
        }

        List<Piece> pieces = new ArrayList<>();
        if (starts.isEmpty()) {
            pieces.add(new Piece(null, content));
            return pieces;
        }

        if (starts.get(0) > 0) {
            pieces.add(new Piece(null, content.substring(0, starts.get(0))));
        }
        for (int i = 0; i < starts.size(); i++) {
            int begin = starts.get(i);
            int end = (i + 1 < starts.size()) ? starts.get(i + 1) : content.length();
            pieces.add(new Piece(pages.get(i), content.substring(begin, end)));
        }
        return pieces;
    }

    /** Greedily packs consecutive pieces while the running sum stays within targetChars. */
    private List<RawChunk> packPieces(List<Piece> pieces) {
        List<RawChunk> chunks = new ArrayList<>();
        RawChunk current = null;
        for (Piece p : pieces) {
            if (current == null) {
                current = new RawChunk(p.page(), p.page(), new StringBuilder(p.text()));
            } else if (current.builder().length() + p.text().length() <= props.getTargetChars()) {
                current.builder().append(p.text());
                current.extendPage(p.page());
            } else {
                chunks.add(current);
                current = new RawChunk(p.page(), p.page(), new StringBuilder(p.text()));
            }
        }
        if (current != null) {
            chunks.add(current);
        }
        return chunks;
    }

    /** Splits a single oversized piece into sub-pieces each within maxChars, same page number. */
    private List<RawChunk> splitOversized(RawChunk rc) {
        List<RawChunk> result = new ArrayList<>();
        String remaining = rc.text();
        int max = props.getMaxChars();
        while (remaining.length() > max) {
            int cut = findSplitPoint(remaining, max);
            result.add(new RawChunk(rc.pageFrom(), rc.pageTo(), new StringBuilder(remaining.substring(0, cut))));
            remaining = remaining.substring(cut);
        }
        if (!remaining.isEmpty()) {
            result.add(new RawChunk(rc.pageFrom(), rc.pageTo(), new StringBuilder(remaining)));
        }
        return result;
    }

    /** Prefers the last blank line within the window, then the last sentence end, then a hard cut. */
    private int findSplitPoint(String text, int max) {
        String window = text.substring(0, max);

        Matcher blank = BLANK_LINE.matcher(window);
        int lastBlankEnd = -1;
        while (blank.find()) {
            lastBlankEnd = blank.end();
        }
        if (lastBlankEnd > 0) {
            return lastBlankEnd;
        }

        Matcher sentence = SENTENCE_END.matcher(window);
        int lastSentenceEnd = -1;
        while (sentence.find()) {
            lastSentenceEnd = sentence.end();
        }
        if (lastSentenceEnd > 0) {
            return lastSentenceEnd;
        }

        return max;
    }

    private record Piece(Integer page, String text) {}

    /** Mutable accumulator for a chunk being packed/split, before ordinals are assigned. */
    private static final class RawChunk {
        private Integer pageFrom;
        private Integer pageTo;
        private final StringBuilder builder;

        RawChunk(Integer pageFrom, Integer pageTo, StringBuilder builder) {
            this.pageFrom = pageFrom;
            this.pageTo = pageTo;
            this.builder = builder;
        }

        Integer pageFrom() { return pageFrom; }
        Integer pageTo() { return pageTo; }

        /**
         * Folds another packed piece's page into the bounds. {@code pageFrom} becomes the first
         * non-null page seen so far; {@code pageTo} becomes the last non-null page seen so far. An
         * unmarked piece (page == null) leaves both bounds untouched — in particular an unmarked
         * piece packed ahead of a marked one must not make the chunk report a null lower bound
         * (code review fix round 1, MAJOR).
         */
        void extendPage(Integer page) {
            if (page != null) {
                if (pageFrom == null) {
                    pageFrom = page;
                }
                pageTo = page;
            }
        }

        StringBuilder builder() { return builder; }
        String text() { return builder.toString(); }
    }
}
