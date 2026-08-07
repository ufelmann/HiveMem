package com.hivemem.consumption;

import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/** Pass 2 of the 3-pass reassembly: read ONE upright page and extract assembly-relevant metadata.
 *  One image per call on purpose: per-page calls need no image labels (Haiku cannot reliably map
 *  many unlabeled images to page numbers) and reading upright pages is what made extraction
 *  error-free in validation. */
public class PageMetadataExtractor {

    private static final Logger log = LoggerFactory.getLogger(PageMetadataExtractor.class);

    /** What pass 3 needs to assemble mailings. All String fields nullable.
     *  {@code degraded} means this page contributed no usable metadata to assembly: either both
     *  vision attempts threw, or the reply parsed but every identifying field came back null while
     *  the page was not classified blank (an empty {@code {}} reply, for example). A genuinely
     *  blank page ({@code blank=true} with the rest null) is a successful classification and is
     *  never degraded. */
    public record PageMetadata(int page, String sender, String date, String pageLabel,
                               String docType, String reference, String summary, boolean blank,
                               boolean degraded) {}

    static final String PROMPT = """
            This is ONE page of a scanned German letter/document batch. Read it and extract:
            - sender: the sender/letterhead (company or authority) printed on the page, else null
            - date: the letter's ISSUE date — the date printed in the letterhead/date line or in the
              subject/heading ("Bescheid ... vom 05.09.2025"). NEVER use dates that only appear in the
              body prose (transmission dates like "uebermittelt am ...", references like "Ihr Schreiben
              vom ...", due dates). NEVER use the EDITION date of a printed form, a set of terms or a
              generic notice, whatever label it carries — "Stand: ...", "Fassung 1. Januar 2020",
              "Ausgabe ...", "Version ..." — and note it often sits in the heading right under the
              title, which does NOT make it an issue date. If only such an edition date exists, report
              it prefixed with "Stand " regardless of its original label. If no issue date is visible,
              null.
            - page_label: the page number PRINTED on the page (e.g. "Seite 2 von 2", "2/3");
              vertical print-shop control strings along the edge do NOT count; else null
            - doc_type: short type, e.g. "letter", "contract data sheet", "SEPA mandate",
              "Datenschutz notice", "Widerruf notice", "invoice", "Bescheid", "blank"
            - reference: any contract/customer/file number (Vertrags-Nr, Kunden-Nr, Buchungs-Nr,
              Steuernummer...), else null
            - summary: one short sentence of what the page is
            - blank: true if the page is essentially empty
            Reply with STRICT JSON only:
            {"sender":...,"date":...,"page_label":...,"doc_type":...,"reference":...,"summary":...,"blank":...}""";

    private final VisionMultiClient vision;

    public PageMetadataExtractor(VisionMultiClient vision) {
        this.vision = vision;
    }

    /** Extract metadata for one upright page. Never throws: after one retry it returns a null-row
     *  (sheet pairing in pass 3 still places the page next to its scan neighbors).
     *
     *  @param pixelBlank the page read as near-white before the call. It only matters when BOTH
     *      attempts fail: on a white page the model sometimes answers with prose instead of JSON,
     *      and with the pixel finding in hand that is not a degradation. It never makes the row
     *      blank — only a model verdict may put a page on the delete list — and it never overrides
     *      a reply that did parse. Both rules keep a stamp-only page out of the delete list. */
    public PageMetadata extract(String realm, int page, byte[] uprightPng, boolean pixelBlank) {
        List<VisionMultiClient.Image> images = List.of(
                new VisionMultiClient.Image("image/png", Base64.getEncoder().encodeToString(uprightPng)));
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                JsonNode n = LlmJson.parseObject(vision.group(realm, PROMPT, images));
                String sender = n.path("sender").asString(null);
                String date = n.path("date").asString(null);
                String pageLabel = n.path("page_label").asString(null);
                String docType = n.path("doc_type").asString(null);
                String reference = n.path("reference").asString(null);
                String summary = n.path("summary").asString(null);
                boolean blank = n.path("blank").asBoolean(false);
                boolean degraded = !blank && sender == null && date == null && pageLabel == null
                        && docType == null && reference == null && summary == null;
                return new PageMetadata(page, sender, date, pageLabel, docType, reference, summary,
                        blank, degraded);
            } catch (Exception e) {
                log.warn("Metadata attempt {}/2 failed for page {}: {}", attempt, page, e.toString());
            }
        }
        // Both attempts failed. A page the pixels already called near-white is not a degradation —
        // this is exactly the prose-instead-of-JSON reply a white page provokes — but it is not put
        // on the delete list either: `blank` deletes, and no model verdict exists here. During a
        // provider outage every page fails both attempts, and a stamp-only sheet reads whiter than a
        // real blank backside, so a `blank=true` here would silently drop exactly the page class this
        // design exists to protect. A genuinely white backside is still caught by the untouched 0.995
        // post-check in ReassemblyOrchestrator; `degraded=false` still removes the degradation cause
        // that the lowered queue floor depends on. The cost is an occasional blank page surviving
        // into the archive — the same trade this design already made: rotated is recoverable,
        // deleted is not.
        if (pixelBlank) {
            return new PageMetadata(page, null, null, null, "blank", null,
                    "blank page (no metadata reply, near-white)", false, false);
        }
        return new PageMetadata(page, null, null, null, null, null, null, false, true);
    }
}
