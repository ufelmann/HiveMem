package com.hivemem.consumption;

import com.hivemem.consumption.PageMetadataExtractor.PageMetadata;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic post-processing of pass 3: enforces in Java what MailingAssembler.PROMPT can only
 *  ask for - mailings that share sender + issue date are merged, and the pages of a single complete
 *  labelled document are ordered by their printed labels. Pure function, never throws: an exception
 *  escaping assemble() would degrade the whole batch to one pending document. */
public final class MailingNormalizer {

    /** Only labels carrying BOTH a number and a printed total count. A total-less number cannot be
     *  attributed to a document, and the {1,4} bound keeps parseInt from overflowing. */
    private static final Pattern LABEL = Pattern.compile(
            "^\\s*(?:seite|blatt)?\\s*(\\d{1,4})\\s*(?:von|/)\\s*(\\d{1,4})\\s*$",
            Pattern.CASE_INSENSITIVE);

    record Label(int number, int total) {}

    /** The printed label of a page, or null when it has none, is unparseable, or the page is blank.
     *  Blank pages are deliberately label-less: a mostly-empty back side can still carry a printed
     *  footer, and letting it join a family would move the non-blank pages around it. */
    static Label label(PageMetadata m) {
        if (m == null || m.blank() || m.pageLabel() == null) return null;
        Matcher x = LABEL.matcher(m.pageLabel());
        if (!x.matches()) return null;
        return new Label(Integer.parseInt(x.group(1)), Integer.parseInt(x.group(2)));
    }

    /** Metadata by page number - NEVER by list index: the list is neither 1-based nor contiguous.
     *  A duplicate page number keeps the first entry instead of throwing. */
    static Map<Integer, PageMetadata> byPage(List<PageMetadata> pages) {
        Map<Integer, PageMetadata> meta = new HashMap<>();
        for (PageMetadata m : pages) {
            if (m != null) meta.putIfAbsent(m.page(), m);
        }
        return meta;
    }
}
