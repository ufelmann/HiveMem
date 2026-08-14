package com.hivemem.consumption;

import static org.assertj.core.api.Assertions.assertThat;

import com.hivemem.consumption.PageMetadataExtractor.PageMetadata;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MailingNormalizerTest {

    /** Non-blank page with a printed label and no sender/date. */
    private static PageMetadata labelled(int page, String label) {
        return new PageMetadata(page, null, null, label, "letter", null, "a page", false, false);
    }

    private static PageMetadata blank(int page) {
        return new PageMetadata(page, null, null, null, "blank", null, "empty", true, false);
    }

    private static PageMetadata plain(int page) {
        return new PageMetadata(page, null, null, null, "letter", null, "a page", false, false);
    }

    private static DocGroup group(String id, double confidence, int... pages) {
        DocGroup g = new DocGroup(id, id + " descriptor");
        for (int p : pages) g.pages.add(p);
        g.minConfidence = confidence;
        return g;
    }

    private static PageMetadata letter(int page, String sender, String date) {
        return new PageMetadata(page, sender, date, null, "letter", null, "a letter", false, false);
    }

    private static PageMetadata withLabel(PageMetadata m, String label) {
        return new PageMetadata(m.page(), m.sender(), m.date(), label, m.docType(), m.reference(),
                m.summary(), m.blank(), m.degraded());
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
                new PageMetadata(1, null, null, "Seite 2 von 2", "blank", null, "empty", true, false);
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
                new PageMetadata(9, null, null, "Seite 1 von 2", "blank", null, "empty", true, false),
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
        // The date component is normalized too (normalizeDate): "05.09.2025" -> "2025-09-05".
        assertThat(MailingNormalizer.anchorKey(g, meta))
                .isEqualTo(new MailingNormalizer.AnchorKey("finanzamt musterstadt", "2025-09-05"));
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
                letter(11, "Finanzamt", "stand 01.01.2025"),         // lowercase variant
                new PageMetadata(12, "Finanzamt", "05.09.2025", null, "blank", null, "x", true, false)));
        for (int page = 1; page <= 12; page++) {
            DocGroup g = group("m", 0.9, page);
            assertThat(MailingNormalizer.anchorKey(g, meta)).as("page " + page).isNull();
        }
    }

    @Test
    void skipsAnUnusableStandDateAndAnchorsOnALaterPageToStillMerge() {
        // The enclosure's "Stand ..." print date precedes the letter's real issue date in the
        // same group. anchorKey must skip past the unusable first page and keep looking, not give
        // up on the whole group - otherwise a mailing whose enclosure sorts first could never
        // merge with its sibling at all. Kills a mutant that turns the date guard's `continue`
        // into `return null`.
        DocGroup a = group("a", 0.9, 1, 2);
        DocGroup b = group("b", 0.9, 3);
        List<DocGroup> out = new MailingNormalizer().normalize(List.of(a, b), List.of(
                letter(1, "Finanzamt", "Stand 01.01.2025"),
                letter(2, "Finanzamt", "05.09.2025"),
                letter(3, "Finanzamt", "05.09.2025")));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).pages).containsExactly(1, 2, 3);
    }

    @Test
    void skipsAnUnusablePunctuationOnlySenderAndAnchorsOnALaterPageToStillMerge() {
        // First page's sender is punctuation-only (normalizes to ""); the second page in the same
        // group has a usable sender. anchorKey must skip past the first page and keep looking, not
        // give up on the whole group. Kills a mutant that turns the sender guard's `continue` into
        // `return null`.
        DocGroup a = group("a", 0.9, 1, 2);
        DocGroup b = group("b", 0.9, 3);
        List<DocGroup> out = new MailingNormalizer().normalize(List.of(a, b), List.of(
                letter(1, "-", "05.09.2025"),
                letter(2, "Finanzamt", "05.09.2025"),
                letter(3, "Finanzamt", "05.09.2025")));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).pages).containsExactly(1, 2, 3);
    }

    @Test
    void mergesTwoGroupsWithTheSameSenderAndIssueDate() {
        // The tax case: same Finanzamt, same Bescheid date, differently-read Steuernummer.
        DocGroup a = group("a", 0.9, 1);
        DocGroup b = group("b", 0.4, 2);
        List<DocGroup> out = new MailingNormalizer().normalize(List.of(a, b), List.of(
                new PageMetadata(1, "Finanzamt Musterstadt", "05.09.2025", null, "Bescheid",
                        "12/345/67890", "page one", false, false),
                new PageMetadata(2, "FINANZAMT  MUSTERSTADT", "05.09.2025", null, "Bescheid",
                        "12/345/6789O", "page two", false, false)));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).id).isEqualTo("a");
        assertThat(out.get(0).descriptor).isEqualTo("a descriptor");
        assertThat(out.get(0).pages).containsExactly(1, 2);
        assertThat(out.get(0).minConfidence).isEqualTo(0.4);   // minimum, i.e. towards pending
    }

    @Test
    void keepsGroupsWithDifferentIssueDatesApart() {
        DocGroup a = group("a", 0.9, 1);
        DocGroup b = group("b", 0.9, 2);
        List<DocGroup> out = new MailingNormalizer().normalize(List.of(a, b), List.of(
                letter(1, "Finanzamt", "05.09.2025"), letter(2, "Finanzamt", "06.09.2025")));
        assertThat(out).hasSize(2);
    }

    @Test
    void mergesThreeGroupsIntoTheFirstWithTheGlobalMinimumConfidence() {
        DocGroup a = group("a", 0.9, 1);
        DocGroup b = group("b", 0.3, 2);
        DocGroup c = group("c", 0.6, 3);
        List<DocGroup> out = new MailingNormalizer().normalize(List.of(a, b, c), List.of(
                letter(1, "Finanzamt", "05.09.2025"), letter(2, "Finanzamt", "05.09.2025"),
                letter(3, "Finanzamt", "05.09.2025")));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).pages).containsExactly(1, 2, 3);
        assertThat(out.get(0).minConfidence).isEqualTo(0.3);
    }

    @Test
    void preservesTheOrderOfSurroundingGroups() {
        DocGroup a = group("a", 0.9, 1);
        DocGroup x = group("x", 0.9, 2);
        DocGroup b = group("b", 0.9, 3);
        List<DocGroup> out = new MailingNormalizer().normalize(List.of(a, x, b), List.of(
                letter(1, "Finanzamt", "05.09.2025"), letter(2, "Stadtwerke", "01.02.2025"),
                letter(3, "Finanzamt", "05.09.2025")));
        assertThat(out).extracting(g -> g.id).containsExactly("a", "x");
        assertThat(out.get(0).pages).containsExactly(1, 3);
    }

    @Test
    void leavesAnchorlessGroupsAloneAndInPlace() {
        DocGroup a = group("a", 0.9, 1);
        DocGroup b = group("b", 0.9, 2);
        List<DocGroup> out = new MailingNormalizer()
                .normalize(List.of(a, b), List.of(plain(1), plain(2)));
        assertThat(out).extracting(g -> g.id).containsExactly("a", "b");
    }

    @Test
    void mergeCompletesADocumentAndTheSecondOrderPassSortsIt() {
        DocGroup a = group("a", 0.9, 1, 3);
        DocGroup b = group("b", 0.9, 2);
        List<DocGroup> out = new MailingNormalizer().normalize(List.of(a, b), List.of(
                withLabel(letter(1, "Finanzamt", "05.09.2025"), "Seite 1 von 3"),
                withLabel(letter(2, "Finanzamt", "05.09.2025"), "Seite 2 von 3"),
                withLabel(letter(3, "Finanzamt", "05.09.2025"), "Seite 3 von 3")));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).pages).containsExactly(1, 2, 3);
    }

    @Test
    void insertsAMergedPageBehindItsFamilyNotBehindTheEnclosures() {
        // [L1, E1, E2] + [L2]: appending would strand the letter's page 2 behind the enclosures,
        // and no sort can repair it (the group mixes labelled and unlabelled pages).
        DocGroup a = group("a", 0.9, 1, 2, 3);
        DocGroup b = group("b", 0.9, 4);
        List<DocGroup> out = new MailingNormalizer().normalize(List.of(a, b), List.of(
                withLabel(letter(1, "Finanzamt", "05.09.2025"), "Seite 1 von 2"),
                plain(2), plain(3),
                withLabel(letter(4, "Finanzamt", "05.09.2025"), "Seite 2 von 2")));
        assertThat(out.get(0).pages).containsExactly(1, 4, 2, 3);
    }

    @Test
    void appendsInsteadOfSplicingWhenTheTargetFamilyIsAmbiguous() {
        // The enclosure prints its own "Seite 1 von 2", so the total=2 family holds the number 1
        // twice. Inserting after its last page would split the enclosure - append instead.
        DocGroup a = group("a", 0.9, 1, 2, 3);
        DocGroup b = group("b", 0.9, 4);
        List<DocGroup> out = new MailingNormalizer().normalize(List.of(a, b), List.of(
                withLabel(letter(1, "Finanzamt", "05.09.2025"), "Seite 1 von 2"),
                withLabel(plain(2), "Seite 1 von 2"), plain(3),
                withLabel(letter(4, "Finanzamt", "05.09.2025"), "Seite 2 von 2")));
        assertThat(out.get(0).pages).containsExactly(1, 2, 3, 4);
    }

    @Test
    void appendsInsteadOfSplicingWhenTheIncomingPagesAreAmbiguous() {
        // Two incoming pages both claim "Seite 2 von 2" - one of them is foreign. Neither may be
        // spliced into the letter.
        DocGroup a = group("a", 0.9, 1, 2, 3);
        DocGroup b = group("b", 0.9, 4, 5);
        List<DocGroup> out = new MailingNormalizer().normalize(List.of(a, b), List.of(
                withLabel(letter(1, "Finanzamt", "05.09.2025"), "Seite 1 von 2"),
                plain(2), plain(3),
                withLabel(letter(4, "Finanzamt", "05.09.2025"), "Seite 2 von 2"),
                withLabel(plain(5), "Seite 2 von 2")));
        assertThat(out.get(0).pages).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void appendsAnIncomingFamilyThatHasNoCounterpartInTheTarget() {
        // The target only ever printed "von 5" labels; the incoming page's "von 9" family has no
        // match there at all. Splicing it in at index 0 (a family that was never found) would be
        // worse than doing nothing - append instead.
        DocGroup a = group("a", 0.9, 1);
        DocGroup b = group("b", 0.9, 2);
        List<DocGroup> out = new MailingNormalizer().normalize(List.of(a, b), List.of(
                withLabel(letter(1, "Finanzamt", "05.09.2025"), "Seite 1 von 5"),
                withLabel(letter(2, "Finanzamt", "05.09.2025"), "Seite 1 von 9")));
        assertThat(out.get(0).pages).containsExactly(1, 2);
    }

    @Test
    void recomputesTheInsertPointAfterAnEarlierFamilyInsertionShiftsIndices() {
        // Target: [10 ("1 von 2"), 20 ("1 von 3")]; incoming: [11 ("2 von 2"), 21 ("2 von 3")].
        // Both families are insertable. Inserting the total=2 family right after index 0 shifts
        // page 20 from index 1 to index 2, so the total=3 family must land after index 2 (=> index
        // 3), not after the index 1 computed before that shift. Neither group is one complete
        // labelled document (mixed totals), so the final order() pass cannot mask a wrong result.
        DocGroup a = group("a", 0.9, 10, 20);
        DocGroup b = group("b", 0.9, 11, 21);
        List<DocGroup> out = new MailingNormalizer().normalize(List.of(a, b), List.of(
                withLabel(letter(10, "Finanzamt", "05.09.2025"), "Seite 1 von 2"),
                withLabel(letter(20, "Finanzamt", "05.09.2025"), "Seite 1 von 3"),
                withLabel(letter(11, "Finanzamt", "05.09.2025"), "Seite 2 von 2"),
                withLabel(letter(21, "Finanzamt", "05.09.2025"), "Seite 2 von 3")));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).pages).containsExactly(10, 11, 20, 21);
    }

    @Test
    void toleratesTheSamePageNumberInTwoMergedGroups() {
        // PageReassembler dedupes first-wins downstream; the normalizer must not throw.
        DocGroup a = group("a", 0.9, 1);
        DocGroup b = group("b", 0.9, 1, 2);
        List<DocGroup> out = new MailingNormalizer().normalize(List.of(a, b), List.of(
                letter(1, "Finanzamt", "05.09.2025"), letter(2, "Finanzamt", "05.09.2025")));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).pages).containsExactly(1, 1, 2);
    }

    @Test
    void doesNotMergeABatchWhoseMetadataCarriesNoDates() {
        // Mirrors ReassemblyIT's fixture: sender set, date null throughout.
        DocGroup a = group("a", 0.9, 1, 3);
        DocGroup b = group("b", 0.9, 2);
        List<DocGroup> out = new MailingNormalizer().normalize(List.of(a, b), List.of(
                letter(1, "S", null), letter(2, "S", null), letter(3, "S", null)));
        assertThat(out).hasSize(2);
        assertThat(out.get(0).pages).containsExactly(1, 3);
    }

    @Test
    void normalizesTheDateFormatsTheExtractorActuallyEmits() {
        // Measured on prod 2026-08-07: the same batch reported "2016-09-13" on one page and
        // "13.09.2016" on the next. Both must collapse onto one key.
        assertThat(MailingNormalizer.normalizeDate("13.09.2016")).isEqualTo("2016-09-13");
        assertThat(MailingNormalizer.normalizeDate("2016-09-13")).isEqualTo("2016-09-13");
        assertThat(MailingNormalizer.normalizeDate("13.9.2016")).isEqualTo("2016-09-13");
        assertThat(MailingNormalizer.normalizeDate("  13.09.2016  ")).isEqualTo("2016-09-13");
    }

    @Test
    void normalizesGermanLongDatesIncludingOcrDamagedUmlauts() {
        // OCR renders the same page as "21. März 2016" and "21. Marz 2016" in different passes.
        assertThat(MailingNormalizer.normalizeDate("21. März 2016")).isEqualTo("2016-03-21");
        assertThat(MailingNormalizer.normalizeDate("21. Marz 2016")).isEqualTo("2016-03-21");
        assertThat(MailingNormalizer.normalizeDate("21. Maerz 2016")).isEqualTo("2016-03-21");
        assertThat(MailingNormalizer.normalizeDate("5. September 2025")).isEqualTo("2025-09-05");
        assertThat(MailingNormalizer.normalizeDate("1. Januar 2020")).isEqualTo("2020-01-01");
    }

    @Test
    void leavesUnparseableOrAmbiguousDatesAloneButStillCanonicalisesThem() {
        // A two-digit year is NOT guessed, and an unknown shape must not collapse onto anything.
        assertThat(MailingNormalizer.normalizeDate("13.09.16")).isEqualTo("13.09.16");
        assertThat(MailingNormalizer.normalizeDate("Frühjahr 2016")).isEqualTo("frühjahr 2016");
        assertThat(MailingNormalizer.normalizeDate("  Q3   2016 ")).isEqualTo("q3 2016");
        // A slash is ambiguous between the German (day-first) and American (month-first)
        // convention, and MailingNormalizer can only merge, never split - so it falls back rather
        // than guessing which reading applies.
        assertThat(MailingNormalizer.normalizeDate("13/09/2016")).isEqualTo("13/09/2016");
        // Impossible components fall back rather than rolling over into another month.
        assertThat(MailingNormalizer.normalizeDate("32.09.2016")).isEqualTo("32.09.2016");
        assertThat(MailingNormalizer.normalizeDate("13.13.2016")).isEqualTo("13.13.2016");
        assertThat(MailingNormalizer.normalizeDate("31.02.2016")).isEqualTo("31.02.2016");
    }

    @Test
    void neverThrowsOnDegenerateDateInput() {
        assertThat(MailingNormalizer.normalizeDate("")).isEqualTo("");
        assertThat(MailingNormalizer.normalizeDate("   ")).isEqualTo("");
        assertThat(MailingNormalizer.normalizeDate("99999999999999999999.01.2016"))
                .isEqualTo("99999999999999999999.01.2016");
    }

    @Test
    void keepsTwoGenuinelyDifferentDatesApartAcrossFormats() {
        // The expensive direction: a too-lenient parser would fuse two strangers' letters, and
        // MailingNormalizer can merge but never split.
        assertThat(MailingNormalizer.normalizeDate("05.09.2025"))
                .isNotEqualTo(MailingNormalizer.normalizeDate("09.05.2025"));
        // Same property across two genuinely different formats: an ISO date and a German long-form
        // date that denote different calendar days must not collapse onto the same key.
        assertThat(MailingNormalizer.normalizeDate("2025-09-05"))
                .isNotEqualTo(MailingNormalizer.normalizeDate("6. September 2025"));
    }

    @Test
    void mergesTwoGroupsWhoseDatesDifferOnlyInFormat() {
        // The production case: page 6 reported ISO, page 7 German numeric, same letter.
        DocGroup a = group("a", 0.9, 1);
        DocGroup b = group("b", 0.9, 2);
        List<DocGroup> out = new MailingNormalizer().normalize(List.of(a, b), List.of(
                letter(1, "Sparkasse Musterstadt", "2016-09-13"),
                letter(2, "Sparkasse Musterstadt", "13.09.2016")));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).pages).containsExactly(1, 2);
    }

    @Test
    void normalizesReferencesForComparison() {
        // Null and blank collapse to the empty string: callers treat "" as "no reference".
        assertThat(MailingNormalizer.normalizeReference(null)).isEmpty();
        assertThat(MailingNormalizer.normalizeReference("")).isEmpty();
        assertThat(MailingNormalizer.normalizeReference("  ")).isEmpty();
        assertThat(MailingNormalizer.normalizeReference("-/. ")).isEmpty();

        // Separators are dropped, letters upper-cased.
        assertThat(MailingNormalizer.normalizeReference("12/345/67890")).isEqualTo("1234567890");
        assertThat(MailingNormalizer.normalizeReference("ab-12 34")).isEqualTo("A81234");

        // Each OCR confusable maps onto its digit.
        assertThat(MailingNormalizer.normalizeReference("OILSBZG")).isEqualTo("0115826");

        // A label prefix is NOT stripped — letters are alphanumeric. The containment clause in
        // clearlyDifferent is what handles prefixes; see the spec, section 4.1.
        assertThat(MailingNormalizer.normalizeReference("Service-Nr. 1000000.1"))
                .isEqualTo("5ERV1CENR10000001");
    }

    @Test
    void measuresEditDistance() {
        assertThat(MailingNormalizer.levenshtein("", "")).isZero();
        assertThat(MailingNormalizer.levenshtein("abc", "abc")).isZero();
        assertThat(MailingNormalizer.levenshtein("", "abc")).isEqualTo(3);
        assertThat(MailingNormalizer.levenshtein("abc", "")).isEqualTo(3);
        assertThat(MailingNormalizer.levenshtein("1234567890", "123456780")).isEqualTo(1);
        assertThat(MailingNormalizer.levenshtein("10000001", "22222222")).isEqualTo(8);
    }

    @Test
    void treatsOnlyUnmistakablyDifferentReferencesAsDifferent() {
        // No usable reference on either side -> never a reason to split.
        assertThat(MailingNormalizer.clearlyDifferent(null, "1000000.1")).isFalse();
        assertThat(MailingNormalizer.clearlyDifferent("1000000.1", null)).isFalse();
        assertThat(MailingNormalizer.clearlyDifferent("", "1000000.1")).isFalse();
        assertThat(MailingNormalizer.clearlyDifferent("-/.", "1000000.1")).isFalse();

        // The tax batch: the trailing zero read as the letter O. Same number.
        assertThat(MailingNormalizer.clearlyDifferent("12/345/67890", "12/345/6789O")).isFalse();

        // A dropped digit is OCR noise, not a different number.
        assertThat(MailingNormalizer.clearlyDifferent("12/345/67890", "12/345/6780")).isFalse();

        // Short references need more than one differing character.
        assertThat(MailingNormalizer.clearlyDifferent("4711", "4712")).isFalse();

        // Containment: the same reference with and without its label prefix.
        assertThat(MailingNormalizer.clearlyDifferent("1000000.1", "Service-Nr. 1000000.1"))
                .isFalse();

        // Below the floor a reference is unusable evidence, so it can never split - superseding the
        // pre-amendment behaviour where a 3-character reference fell through to the distance gate.
        assertThat(MailingNormalizer.clearlyDifferent("471", "9999471999")).isFalse();

        // Two genuinely different reference numbers -> split, prefixed or not.
        assertThat(MailingNormalizer.clearlyDifferent("1000000.1", "2222222.2")).isTrue();
        assertThat(MailingNormalizer.clearlyDifferent(
                "Service-Nr. 1000000.1", "Service-Nr. 2222222.2")).isTrue();
    }

    @Test
    void treatsAReferenceBelowTheContainmentFloorAsUnusable() {
        // One or two characters surviving normalization is OCR debris, not an Aktenzeichen.
        // Splitting two letters on that evidence is the failure direction the design rules out.
        assertThat(MailingNormalizer.clearlyDifferent("1", "22")).isFalse();
        assertThat(MailingNormalizer.clearlyDifferent("12", "34")).isFalse();
        assertThat(MailingNormalizer.clearlyDifferent("12", "3456")).isFalse();
        assertThat(MailingNormalizer.clearlyDifferent("123", "9999888877")).isFalse();

        // The floor applies to each side independently: a usable reference opposite an unusable one
        // still cannot split.
        assertThat(MailingNormalizer.clearlyDifferent("1000000.1", "12")).isFalse();
        assertThat(MailingNormalizer.clearlyDifferent("12", "1000000.1")).isFalse();

        // Exactly at the floor the rule still works: four characters are comparable.
        assertThat(MailingNormalizer.clearlyDifferent("1111", "2222")).isTrue();
        // ...and remains tolerant of one differing character at that length.
        assertThat(MailingNormalizer.clearlyDifferent("4711", "4712")).isFalse();
    }

    @Test
    void anchorReferenceComesFromTheAnchorPageNotJustAnyPage() {
        // Page 1 cannot anchor (enclosure print date), page 2 can. The reference must come from
        // page 2 — an enclosure routinely carries a foreign number (a form ID, a publisher's
        // number), and letting that decide would split a letter from its own enclosures.
        DocGroup g = group("a", 0.9, 1, 2, 3);
        Map<Integer, PageMetadata> meta = MailingNormalizer.byPage(List.of(
                new PageMetadata(1, "Kasse", "Stand 01.01.2025", null, "terms",
                        "FORM-999", "enclosure", false, false),
                new PageMetadata(2, "Kasse", "05.09.2025", null, "letter",
                        "1000000.1", "the letter", false, false),
                new PageMetadata(3, "Kasse", "05.09.2025", null, "letter",
                        "2222222.2", "later page", false, false)));

        assertThat(MailingNormalizer.anchorReference(g, meta)).isEqualTo("1000000.1");
    }

    @Test
    void anchorReferenceIsNullWhenTheGroupHasNoAnchor() {
        DocGroup g = group("a", 0.9, 1);
        Map<Integer, PageMetadata> meta = MailingNormalizer.byPage(List.of(
                new PageMetadata(1, null, null, null, "letter", "1000000.1", "no anchor",
                        false, false)));

        assertThat(MailingNormalizer.anchorReference(g, meta)).isNull();
    }

    @Test
    void keepsSameSenderAndDateApartWhenReferencesAreClearlyDifferent() {
        // The insurer case: several letters sent on one day, distinguished only by their
        // reference numbers. Before this rule they were forced into one document.
        DocGroup a = group("a", 0.9, 1);
        DocGroup b = group("b", 0.8, 2);
        List<DocGroup> out = new MailingNormalizer().normalize(List.of(a, b), List.of(
                new PageMetadata(1, "Kasse", "20.02.2024", null, "letter",
                        "1000000.1", "first letter", false, false),
                new PageMetadata(2, "Kasse", "20.02.2024", null, "letter",
                        "2222222.2", "second letter", false, false)));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).pages).containsExactly(1);
        assertThat(out.get(1).pages).containsExactly(2);
    }

    @Test
    void stillMergesWhenOneSideHasNoReference() {
        DocGroup a = group("a", 0.9, 1);
        DocGroup b = group("b", 0.8, 2);
        List<DocGroup> out = new MailingNormalizer().normalize(List.of(a, b), List.of(
                new PageMetadata(1, "Kasse", "20.02.2024", null, "letter",
                        "1000000.1", "the letter", false, false),
                new PageMetadata(2, "Kasse", "20.02.2024", null, "letter",
                        null, "continuation", false, false)));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).pages).containsExactly(1, 2);
    }

    @Test
    void stillMergesWhenTheReferenceOnlyCarriesALabelPrefix() {
        DocGroup a = group("a", 0.9, 1);
        DocGroup b = group("b", 0.8, 2);
        List<DocGroup> out = new MailingNormalizer().normalize(List.of(a, b), List.of(
                new PageMetadata(1, "Kasse", "20.02.2024", null, "letter",
                        "1000000.1", "the letter", false, false),
                new PageMetadata(2, "Kasse", "20.02.2024", null, "letter",
                        "Service-Nr. 1000000.1", "same letter", false, false)));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).pages).containsExactly(1, 2);
    }

    @Test
    void groupsEachIncomingGroupWithItsOwnReference() {
        // Three groups, two of them the same mailing. The third must not be swallowed by the
        // first just because it shares sender and date.
        DocGroup a = group("a", 0.9, 1);
        DocGroup b = group("b", 0.8, 2);
        DocGroup c = group("c", 0.7, 3);
        List<DocGroup> out = new MailingNormalizer().normalize(List.of(a, b, c), List.of(
                new PageMetadata(1, "Kasse", "20.02.2024", null, "letter",
                        "1000000.1", "letter one", false, false),
                new PageMetadata(2, "Kasse", "20.02.2024", null, "letter",
                        "2222222.2", "letter two", false, false),
                new PageMetadata(3, "Kasse", "20.02.2024", null, "letter",
                        "1000000.1", "letter one, continued", false, false)));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).pages).containsExactly(1, 3);
        assertThat(out.get(1).pages).containsExactly(2);
        assertThat(out.get(0).minConfidence).isEqualTo(0.7);   // minimum across the absorbed pair
    }

    /** {@code clearlyDifferent} is a similarity test, not an equivalence relation: this triple has
     *  d(A,B)=2, d(B,C)=2, both at the merge threshold, but d(A,C)=4, over it - so A and C are
     *  clearly different while neither is clearly different from B. First-match-wins over such a
     *  predicate makes the partition depend on arrival order.
     *
     *  <p>The two outcomes below were MEASURED by running all six permutations of A, B, C through
     *  {@code normalize} after the Candidate-capture fix (see {@code merge}'s javadoc), not derived
     *  on paper: order B,A,C merges everything into one document, order A,B,C splits C off into its
     *  own document. Both outcomes are legitimate under the "merge when unsure" bias - this test
     *  exists only so a future refactor cannot silently change which orders merge and which split. */
    @Test
    void tripleWithNonTransitiveDistancesSplitsOrMergesDependingOnArrivalOrder() {
        String refA = "10000000";
        String refB = "10000022";
        String refC = "10002222";

        // Measured: B, A, C -> one document (all three merge).
        DocGroup b1 = group("b", 0.9, 2);
        DocGroup a1 = group("a", 0.8, 1);
        DocGroup c1 = group("c", 0.7, 3);
        List<DocGroup> merged = new MailingNormalizer().normalize(List.of(b1, a1, c1), List.of(
                new PageMetadata(2, "Kasse", "20.02.2024", null, "letter", refB, "letter b",
                        false, false),
                new PageMetadata(1, "Kasse", "20.02.2024", null, "letter", refA, "letter a",
                        false, false),
                new PageMetadata(3, "Kasse", "20.02.2024", null, "letter", refC, "letter c",
                        false, false)));
        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).pages).containsExactlyInAnyOrder(1, 2, 3);

        // Measured: A, B, C -> two documents ({A, B} together, C split off).
        DocGroup a2 = group("a", 0.9, 1);
        DocGroup b2 = group("b", 0.8, 2);
        DocGroup c2 = group("c", 0.7, 3);
        List<DocGroup> split = new MailingNormalizer().normalize(List.of(a2, b2, c2), List.of(
                new PageMetadata(1, "Kasse", "20.02.2024", null, "letter", refA, "letter a",
                        false, false),
                new PageMetadata(2, "Kasse", "20.02.2024", null, "letter", refB, "letter b",
                        false, false),
                new PageMetadata(3, "Kasse", "20.02.2024", null, "letter", refC, "letter c",
                        false, false)));
        assertThat(split).hasSize(2);
        assertThat(split.get(0).pages).containsExactlyInAnyOrder(1, 2);
        assertThat(split.get(1).pages).containsExactly(3);
    }

    @Test
    void mergesWhenTheDigitsMatchThroughDifferentLabelWords() {
        // The reference field is free-form prose, not a stable identifier: the extractor re-words it
        // per page. When BOTH sides carry a label and the labels differ, the prefixes dominate the
        // edit distance and the rule used to split although the number was identical. Shapes below
        // are synthetic but mirror pairs actually observed in one production corpus.
        assertThat(MailingNormalizer.clearlyDifferent(
                "T100000 / Personalnummer 1871",
                "T100000 (employee ID), 1871 (personnel number)")).isFalse();
        assertThat(MailingNormalizer.clearlyDifferent(
                "Vertrags-Nr. 1000000.1", "Kunden-Nr. 1000000.1")).isFalse();
        assertThat(MailingNormalizer.clearlyDifferent(
                "Konto-Nr. 6100000", "Kontonummer 6100000")).isFalse();

        // The guard needs at least four digits, so a stray digit cannot glue two strangers together.
        assertThat(MailingNormalizer.clearlyDifferent(
                "Vorgang 12", "Sammelmappe 9912345678")).isTrue();

        // It must not disarm the feature: genuinely different numbers still split, labelled or not.
        assertThat(MailingNormalizer.clearlyDifferent("1000000.1", "2222222.2")).isTrue();
        assertThat(MailingNormalizer.clearlyDifferent(
                "Service-Nr. 1000000.1", "Service-Nr. 2222222.2")).isTrue();
    }
}
