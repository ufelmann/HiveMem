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
}
