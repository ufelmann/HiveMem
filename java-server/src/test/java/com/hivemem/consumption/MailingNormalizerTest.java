package com.hivemem.consumption;

import static org.assertj.core.api.Assertions.assertThat;

import com.hivemem.consumption.PageMetadataExtractor.PageMetadata;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MailingNormalizerTest {

    /** Non-blank page with a printed label and no sender/date. */
    private static PageMetadata labelled(int page, String label) {
        return new PageMetadata(page, null, null, label, "letter", null, "a page", false);
    }

    private static PageMetadata blank(int page) {
        return new PageMetadata(page, null, null, null, "blank", null, "empty", true);
    }

    private static PageMetadata plain(int page) {
        return new PageMetadata(page, null, null, null, "letter", null, "a page", false);
    }

    private static DocGroup group(String id, double confidence, int... pages) {
        DocGroup g = new DocGroup(id, id + " descriptor");
        for (int p : pages) g.pages.add(p);
        g.minConfidence = confidence;
        return g;
    }

    private static PageMetadata letter(int page, String sender, String date) {
        return new PageMetadata(page, sender, date, null, "letter", null, "a letter", false);
    }

    @Test
    void parsesTheAcceptedLabelShapes() {
        assertThat(MailingNormalizer.label(labelled(1, "Seite 2 von 3")))
                .isEqualTo(new MailingNormalizer.Label(2, 3));
        assertThat(MailingNormalizer.label(labelled(1, "2/3")))
                .isEqualTo(new MailingNormalizer.Label(2, 3));
        assertThat(MailingNormalizer.label(labelled(1, "Blatt 2 von 3")))
                .isEqualTo(new MailingNormalizer.Label(2, 3));
        assertThat(MailingNormalizer.label(labelled(1, "Seite 2/3")))
                .isEqualTo(new MailingNormalizer.Label(2, 3));
        assertThat(MailingNormalizer.label(labelled(1, "SEITE 2 VON 3")))
                .isEqualTo(new MailingNormalizer.Label(2, 3));
        assertThat(MailingNormalizer.label(labelled(1, "  2 von 3  ")))
                .isEqualTo(new MailingNormalizer.Label(2, 3));
    }

    @Test
    void rejectsEverythingWithoutBothNumberAndTotal() {
        // A total-less number cannot be attributed to a document: a letter's "- 2 -" and a
        // one-page enclosure's "Seite 1" would otherwise share a family and cross-sort.
        for (String bad : List.of("- 2 -", "Seite 2", "2", "III", "Blatt 2", "Seite zwei von drei",
                "x2 von 3", "2 von 3 Seiten", "")) {
            assertThat(MailingNormalizer.label(labelled(1, bad))).as(bad).isNull();
        }
        assertThat(MailingNormalizer.label(labelled(1, null))).isNull();
        assertThat(MailingNormalizer.label(null)).isNull();
    }

    @Test
    void rejectsOverflowDigitsSoParseIntCannotThrow() {
        assertThat(MailingNormalizer.label(labelled(1, "Seite 99999999999 von 99999999999")))
                .isNull();
        assertThat(MailingNormalizer.label(labelled(1, "12345 von 12345"))).isNull();
    }

    @Test
    void aBlankPageIsNeverLabelled() {
        // Blank pages never take part in a label family anywhere - not in ordering, not in the
        // merge insertion rule. Returning null here is the single place that guarantees it.
        PageMetadata blankWithFooterLabel =
                new PageMetadata(1, null, null, "Seite 2 von 2", "blank", null, "empty", true);
        assertThat(MailingNormalizer.label(blankWithFooterLabel)).isNull();
    }

    @Test
    void metadataIsKeyedByPageNumberAndToleratesDuplicatesFirstWins() {
        // The list is neither 1-based nor contiguous, so an index lookup would be wrong; and a
        // duplicate page() must not throw the way Collectors.toMap would.
        Map<Integer, PageMetadata> meta = MailingNormalizer.byPage(List.of(
                labelled(12, "Seite 1 von 2"),
                labelled(17, "Seite 2 von 2"),
                labelled(12, "Seite 9 von 9")));
        assertThat(meta.keySet()).containsExactlyInAnyOrder(12, 17);
        assertThat(meta.get(12).pageLabel()).isEqualTo("Seite 1 von 2");
    }

    @Test
    void sortsACompleteLabelledDocumentAscending() {
        DocGroup g = group("m", 0.9, 5, 4, 6);
        List<DocGroup> out = new MailingNormalizer().normalize(List.of(g), List.of(
                labelled(4, "Seite 1 von 3"),
                labelled(5, "Seite 2 von 3"),
                labelled(6, "Seite 3 von 3")));
        assertThat(out.get(0).pages).containsExactly(4, 5, 6);
    }

    @Test
    void leavesAnIncompleteLabelSequenceUntouched() {
        // 1 von 3 and 3 von 3 only - not a complete 1..N, so we do not guess.
        DocGroup g = group("m", 0.9, 6, 4);
        List<DocGroup> out = new MailingNormalizer().normalize(List.of(g), List.of(
                labelled(4, "Seite 1 von 3"), labelled(6, "Seite 3 von 3")));
        assertThat(out.get(0).pages).containsExactly(6, 4);
    }

    @Test
    void leavesDuplicateLabelNumbersUntouched() {
        DocGroup g = group("m", 0.9, 1, 2, 3);
        List<DocGroup> out = new MailingNormalizer().normalize(List.of(g), List.of(
                labelled(1, "Seite 1 von 3"), labelled(2, "Seite 1 von 3"),
                labelled(3, "Seite 3 von 3")));
        assertThat(out.get(0).pages).containsExactly(1, 2, 3);
    }

    @Test
    void neverInterleavesALetterAndAnEnclosure() {
        // Letter pages 1-3 ("von 3") and a two-page enclosure ("von 2"), emitted correctly.
        // A global or family-local sort would splice them; the group is not ONE document, so
        // nothing is reordered.
        DocGroup g = group("m", 0.9, 1, 2, 3, 4, 5);
        List<DocGroup> out = new MailingNormalizer().normalize(List.of(g), List.of(
                labelled(1, "Seite 1 von 3"), labelled(2, "Seite 2 von 3"),
                labelled(3, "Seite 3 von 3"), labelled(4, "Seite 1 von 2"),
                labelled(5, "Seite 2 von 2")));
        assertThat(out.get(0).pages).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void leavesAPartiallyLabelledGroupUntouched() {
        // Vision read only two of four labels. The total=2 family {L2:2, E1:1} is even complete -
        // sorting it would splice the enclosure into the letter. Group-level completeness refuses.
        DocGroup g = group("m", 0.9, 1, 2, 3, 4);
        List<DocGroup> out = new MailingNormalizer().normalize(List.of(g), List.of(
                plain(1), labelled(2, "Seite 2 von 2"), labelled(3, "Seite 1 von 2"), plain(4)));
        assertThat(out.get(0).pages).containsExactly(1, 2, 3, 4);
    }

    @Test
    void movesBlankPagesToTheEndAndIgnoresTheirLabels() {
        DocGroup g = group("m", 0.9, 9, 7, 8);
        List<DocGroup> out = new MailingNormalizer().normalize(List.of(g), List.of(
                new PageMetadata(9, null, null, "Seite 1 von 2", "blank", null, "empty", true),
                labelled(7, "Seite 2 von 2"), labelled(8, "Seite 1 von 2")));
        // the blank's label does not join the family; pages 7+8 form a complete 1..2 and sort
        assertThat(out.get(0).pages).containsExactly(8, 7, 9);
    }

    @Test
    void keepsUnlabelledPagesInTheirEmittedOrder() {
        DocGroup g = group("m", 0.9, 16, 15, 14, 12);
        List<DocGroup> out = new MailingNormalizer()
                .normalize(List.of(g), List.of(plain(12), plain(14), plain(15), plain(16)));
        assertThat(out.get(0).pages).containsExactly(16, 15, 14, 12);
    }

    @Test
    void toleratesPagesWithoutMetadataAndNeverThrows() {
        // p.asInt() defaults to 0 and the model hallucinates page numbers; PageReassembler filters
        // those later. Here they are ordinary input: unlabelled, non-blank, position kept.
        DocGroup g = group("m", 0.9, 0, 3, 99);
        List<DocGroup> out = new MailingNormalizer()
                .normalize(List.of(g), List.of(plain(3)));
        assertThat(out.get(0).pages).containsExactly(0, 3, 99);
    }

    @Test
    void leavesAnExtraSpuriousPageUntouchedEvenIfNumbersCoverTheRange() {
        // All four pages print "von 3": one page beyond the family the label total claims.
        // Kills a mutant that drops the size==total check (numbers 1..3 all present, so the
        // range-coverage loop alone would wrongly accept this as complete).
        DocGroup g = group("m", 0.9, 19, 22, 21, 20);
        List<DocGroup> out = new MailingNormalizer().normalize(List.of(g), List.of(
                labelled(19, "Seite 4 von 3"), labelled(22, "Seite 3 von 3"),
                labelled(21, "Seite 2 von 3"), labelled(20, "Seite 1 von 3")));
        assertThat(out.get(0).pages).containsExactly(19, 22, 21, 20);
    }

    @Test
    void leavesMismatchedTotalsUntouchedEvenWithoutNumberCollisions() {
        // Two pages, non-colliding numbers {1,2}, but they disagree on the total (2 vs 3) - two
        // different documents that happen to interleave cleanly. Kills a mutant that drops the
        // shared-total check (page count would then coincidentally equal the first page's total,
        // and 1..2 would look "complete").
        DocGroup g = group("m", 0.9, 10, 11);
        List<DocGroup> out = new MailingNormalizer().normalize(List.of(g), List.of(
                labelled(10, "Seite 2 von 2"), labelled(11, "Seite 1 von 3")));
        assertThat(out.get(0).pages).containsExactly(10, 11);
    }

    @Test
    void handlesDegenerateInput() {
        assertThat(new MailingNormalizer().normalize(List.of(), List.of())).isEmpty();
        DocGroup empty = new DocGroup("e", "no pages");
        assertThat(new MailingNormalizer().normalize(List.of(empty), List.of()).get(0).pages)
                .isEmpty();
    }

    @Test
    void normalizesSenderCasingWhitespaceAndPunctuation() {
        assertThat(MailingNormalizer.normalizeSender("Finanzamt Musterstadt"))
                .isEqualTo("finanzamt musterstadt");
        assertThat(MailingNormalizer.normalizeSender("FINANZAMT  MUSTERSTADT"))
                .isEqualTo("finanzamt musterstadt");
        assertThat(MailingNormalizer.normalizeSender(" Finanzamt-Musterstadt. "))
                .isEqualTo("finanzamt musterstadt");
    }

    @Test
    void anchorsOnTheFirstUsablePage() {
        DocGroup g = group("m", 0.9, 1, 2);
        Map<Integer, PageMetadata> meta = MailingNormalizer.byPage(List.of(
                plain(1), letter(2, "Finanzamt Musterstadt", "05.09.2025")));
        assertThat(MailingNormalizer.anchorKey(g, meta))
                .isEqualTo("finanzamt musterstadt 05.09.2025");
    }

    @Test
    void refusesToAnchorOnUnusableMetadata() {
        Map<Integer, PageMetadata> meta = MailingNormalizer.byPage(List.of(
                letter(1, null, "05.09.2025"),                       // no sender
                letter(2, "Finanzamt", null),                        // no date - the common case
                letter(3, "", "05.09.2025"),                         // empty sender
                letter(4, "   ", "05.09.2025"),                      // blank sender
                letter(5, "-", "05.09.2025"),                        // punctuation-only sender
                letter(6, "Finanzamt", ""),                          // empty date
                letter(7, "Finanzamt", "   "),                       // blank date
                letter(8, "Finanzamt", "Stand 01.01.2025"),          // enclosure print date
                letter(9, "Finanzamt", " Stand 01.01.2025"),         // leading blank
                letter(10, "Finanzamt", "Stand: 01.01.2025"),        // colon variant
                new PageMetadata(11, "Finanzamt", "05.09.2025", null, "blank", null, "x", true)));
        for (int page = 1; page <= 11; page++) {
            DocGroup g = group("m", 0.9, page);
            assertThat(MailingNormalizer.anchorKey(g, meta)).as("page " + page).isNull();
        }
    }
}
