package com.hivemem.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CellChunker}, covering every case from design §5.1. The reconstruction test and
 * the threshold test are the load-bearing ones — see the comments on each.
 */
class CellChunkerTest {

    private final ChunkProperties props = new ChunkProperties();
    private final CellChunker chunker = new CellChunker(props);

    // -- helper: builds a repeatable, non-degenerate filler text of an exact length --------------
    private static String filler(int length) {
        StringBuilder sb = new StringBuilder(length);
        String word = "lorem ipsum dolor sit amet consectetur adipiscing elit sed do ";
        while (sb.length() < length) {
            sb.append(word);
        }
        return sb.substring(0, length);
    }

    @Test
    void smallPagesArePackedUpToTargetCharsWithCorrectPageSpan() {
        // Three small pages, each well under targetChars (2000); packed together should combine
        // pages 1-2 into the first chunk and leave page 3 needing its own once the sum would exceed
        // targetChars.
        String page1 = "[page=1]" + filler(900);
        String page2 = "[page=2]" + filler(900);
        String page3 = "[page=3]" + filler(900);
        String content = page1 + page2 + page3;

        List<Chunk> chunks = chunker.chunk(content);

        assertThat(chunks).isNotEmpty();
        // page1+page2 (908+908=1816) fits under targetChars=2000; adding page3 would push to 2724 > 2000.
        assertThat(chunks.get(0).pageFrom()).isEqualTo(1);
        assertThat(chunks.get(0).pageTo()).isEqualTo(2);
        assertThat(chunks.get(1).pageFrom()).isEqualTo(3);
        assertThat(chunks.get(1).pageTo()).isEqualTo(3);
    }

    @Test
    void pageAboveMaxCharsIsSplitAtBlankLines() {
        // A single page far above maxChars (3000), built from paragraphs separated by blank lines,
        // so the split must land on those blank lines and every part must stay within maxChars.
        String paragraph = filler(1000);
        String content = "[page=1]" + paragraph + "\n\n" + paragraph + "\n\n" + paragraph + "\n\n" + paragraph;

        List<Chunk> chunks = chunker.chunk(content);

        assertThat(chunks).hasSizeGreaterThan(1);
        for (Chunk c : chunks) {
            assertThat(c.content().length()).isLessThanOrEqualTo(props.getMaxChars());
            assertThat(c.pageFrom()).isEqualTo(1);
            assertThat(c.pageTo()).isEqualTo(1);
        }
    }

    @Test
    void pageAboveMaxCharsWithoutBlankLinesIsSplitAtSentenceEnds() {
        // Same oversized single page, but no blank lines anywhere — must fall back to sentence ends.
        String sentence = filler(200) + ". ";
        StringBuilder body = new StringBuilder();
        while (body.length() < 3500) {
            body.append(sentence);
        }
        String content = "[page=1]" + body;

        List<Chunk> chunks = chunker.chunk(content);

        assertThat(chunks).hasSizeGreaterThan(1);
        for (Chunk c : chunks) {
            assertThat(c.content().length()).isLessThanOrEqualTo(props.getMaxChars());
        }
        // Every chunk but possibly the last should end right after a sentence terminator.
        for (int i = 0; i < chunks.size() - 1; i++) {
            String text = chunks.get(i).content();
            assertThat(text).endsWith(". ");
        }
    }

    @Test
    void pageAboveMaxCharsWithoutBlankLinesOrSentenceEndsIsSplitHard() {
        // No blank lines, no sentence terminators anywhere — must fall back to a hard cut exactly
        // at maxChars.
        String content = "[page=1]" + "x".repeat(3500);

        List<Chunk> chunks = chunker.chunk(content);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.get(0).content().length()).isEqualTo(props.getMaxChars());
        for (Chunk c : chunks) {
            assertThat(c.content().length()).isLessThanOrEqualTo(props.getMaxChars());
        }
    }

    @Test
    void contentWithoutPageMarkersYieldsNullPageBounds() {
        // Long enough (multi-chunk after a hard split) so we actually get chunks back to inspect.
        String content = "x".repeat(3500);

        List<Chunk> chunks = chunker.chunk(content);

        assertThat(chunks).isNotEmpty();
        for (Chunk c : chunks) {
            assertThat(c.pageFrom()).isNull();
            assertThat(c.pageTo()).isNull();
        }
    }

    @Test
    void unrecognizedPageMarkerShapeCountsAsUnmarked() {
        // "[page=" in a shape that does not match \[page=(\d+)\] exactly must be treated as plain
        // text, not as a marker. Four real cells look like this (design §3.7).
        String content = "intro [page=abc] more text [page=12x] tail " + filler(3000);

        List<Chunk> chunks = chunker.chunk(content);

        // Whatever the outcome, no chunk may carry a non-null page — the malformed markers must
        // never have been recognized.
        for (Chunk c : chunks) {
            assertThat(c.pageFrom()).isNull();
            assertThat(c.pageTo()).isNull();
        }
    }

    @Test
    void oneChunkCoveringEverythingYieldsAnEmptyList() {
        // Below maxChars, single unpaginated piece: packs into exactly one chunk that covers
        // everything -> rule 6 suppresses it.
        String content = filler(1500);

        List<Chunk> chunks = chunker.chunk(content);

        assertThat(chunks).isEmpty();
    }

    @Test
    void thresholdSinglePieceContentOnlyYieldsChunksAboveMaxChars() {
        // Single-piece (unpaginated) content: everything at or below maxChars (3000) packs into one
        // all-covering chunk, which rule 6 suppresses. Only above maxChars do chunks appear. This is
        // NOT "nothing at/below minCellChars, something above it" — that formulation was retracted
        // (design §3.3, §5.1, Befund M2). minCellChars (2000) equals targetChars here, so content
        // between 2000 and 3000 chars must still yield nothing.
        assertThat(chunker.chunk(filler(props.getMinCellChars()))).isEmpty();
        assertThat(chunker.chunk(filler(props.getMinCellChars() + 500))).isEmpty(); // 2500, still <= maxChars
        assertThat(chunker.chunk(filler(props.getMaxChars()))).isEmpty(); // exactly maxChars
        assertThat(chunker.chunk(filler(props.getMaxChars() + 1))).isNotEmpty(); // one char above
    }

    @Test
    void thresholdMultiPageContentYieldsChunksFromMinCellCharsUp() {
        // Multi-page content: because targetChars == minCellChars (2000) here, two pages whose
        // combined length exceeds targetChars already fail to pack into a single covering chunk and
        // so already yield chunks, well below maxChars.
        String page1 = "[page=1]" + filler(1900);
        String page2 = "[page=2]" + filler(200); // combined pieces exceed targetChars(2000)

        List<Chunk> chunks = chunker.chunk(page1 + page2);

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void ordinalsAreGaplessAndInReadingOrder() {
        String page1 = "[page=1]" + filler(1900);
        String page2 = "[page=2]" + filler(1900);
        String page3 = "[page=3]" + filler(1900);

        List<Chunk> chunks = chunker.chunk(page1 + page2 + page3);

        List<Integer> ordinals = chunks.stream().map(Chunk::ordinal).collect(Collectors.toList());
        assertThat(ordinals).isSorted();
        for (int i = 0; i < ordinals.size(); i++) {
            assertThat(ordinals.get(i)).isEqualTo(i);
        }
    }

    @Test
    void reconstructionConcatenatingChunkTextsYieldsEveryCharacterOfTheOriginalExactlyOnce() {
        // The load-bearing test: no overlap, no loss. Mixes packed small pages with an oversized
        // page that gets split at blank lines, at sentence ends, and hard.
        String small1 = "[page=1]" + filler(500);
        String small2 = "[page=2]" + filler(500);
        String blankSplitPage = "[page=3]" + filler(1500) + "\n\n" + filler(1500) + "\n\n" + filler(1500);
        String sentenceSplitPage = "[page=4]" + (filler(300) + ". ").repeat(15);
        String hardSplitPage = "[page=5]" + "y".repeat(3200);
        String unpaginatedTail = "trailing text with no marker at all, " + filler(1000);

        String content = small1 + small2 + blankSplitPage + sentenceSplitPage + hardSplitPage + unpaginatedTail;

        List<Chunk> chunks = chunker.chunk(content);

        assertThat(chunks).isNotEmpty();
        String reconstructed = chunks.stream().map(Chunk::content).collect(Collectors.joining());
        assertThat(reconstructed).isEqualTo(content);
    }

    @Test
    void reconstructionAlsoHoldsForSimpleUnpaginatedOverflow() {
        String content = filler(9000);

        List<Chunk> chunks = chunker.chunk(content);

        String reconstructed = chunks.stream().map(Chunk::content).collect(Collectors.joining());
        assertThat(reconstructed).isEqualTo(content);
    }
}
