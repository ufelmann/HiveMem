package com.hivemem.consumption;

import com.hivemem.consumption.PageMetadataExtractor.PageMetadata;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
        Matcher matcher = LABEL.matcher(m.pageLabel());
        if (!matcher.matches()) return null;
        return new Label(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }

    /** Metadata by page number - NEVER by list index: the list is neither 1-based nor contiguous.
     *  A duplicate page number keeps the first entry instead of throwing. Null-safe so the
     *  class-level "never throws" holds even if a future caller passes null. */
    static Map<Integer, PageMetadata> byPage(List<PageMetadata> pages) {
        Map<Integer, PageMetadata> meta = new HashMap<>();
        if (pages == null) return meta;
        for (PageMetadata m : pages) {
            if (m != null) meta.putIfAbsent(m.page(), m);
        }
        return meta;
    }

    /** Order pages inside every group, merge groups that share an anchor key, order again. The
     *  second pass exists for the case where a merge completes a document: a group holding
     *  "1 von 3" and "3 von 3" becomes sortable only once the merge brings in "2 von 3".
     *  Mutates and returns the given groups - DocGroup.pages is a mutable list. */
    public List<DocGroup> normalize(List<DocGroup> groups, List<PageMetadata> pages) {
        Map<Integer, PageMetadata> meta = byPage(pages);
        for (DocGroup g : groups) order(g, meta);
        List<DocGroup> merged = merge(groups, meta);
        for (DocGroup g : merged) order(g, meta);
        return merged;
    }

    /** Blank pages last; the rest sorted only when the group is one complete labelled document.
     *  Mutates {@code g.pages} in place (clear then addAll) rather than returning a new list. */
    static void order(DocGroup g, Map<Integer, PageMetadata> meta) {
        List<Integer> body = new ArrayList<>();
        List<Integer> blanks = new ArrayList<>();
        for (Integer p : g.pages) {
            PageMetadata m = meta.get(p);
            if (m != null && m.blank()) blanks.add(p);
            else body.add(p);
        }
        if (isOneCompleteDocument(body, meta)) {
            body.sort(Comparator.comparingInt(p -> label(meta.get(p)).number()));
        }
        g.pages.clear();
        g.pages.addAll(body);
        g.pages.addAll(blanks);
    }

    /** True only when every page carries a label, all labels share one total N, there are exactly
     *  N of them and their numbers are exactly 1..N. Anything weaker lets a partially labelled
     *  letter+enclosure pass as one family and splices them. */
    private static boolean isOneCompleteDocument(List<Integer> pages,
                                                 Map<Integer, PageMetadata> meta) {
        if (pages.isEmpty()) return false;
        Set<Integer> numbers = new HashSet<>();
        int total = -1;
        for (Integer p : pages) {
            Label l = label(meta.get(p));
            if (l == null) return false;
            if (total == -1) total = l.total();
            else if (total != l.total()) return false;
            if (!numbers.add(l.number())) return false;
        }
        if (pages.size() != total) return false;
        for (int i = 1; i <= total; i++) {
            if (!numbers.contains(i)) return false;
        }
        return true;
    }

    /** The merge key of a group, or null when nothing in it can anchor. The anchor is the first
     *  non-blank page carrying a usable sender AND a usable issue date. `reference` is deliberately
     *  NOT part of the key: a differently-read Steuernummer is exactly what split the tax batch. */
    static String anchorKey(DocGroup g, Map<Integer, PageMetadata> meta) {
        for (Integer p : g.pages) {
            PageMetadata m = meta.get(p);
            if (m == null || m.blank()) continue;
            if (m.sender() == null || m.date() == null) continue;
            String date = m.date().trim();
            // "Stand ..." is the print date of a generic enclosure (see PageMetadataExtractor.
            // PROMPT); it must never anchor a mailing. Trimmed and without the trailing space so
            // " Stand 01.01.2025" and "Stand: 01.01.2025" cannot slip through.
            if (date.isEmpty() || date.startsWith("Stand")) continue;
            // An unreadable letterhead arrives as "" (asString(null) coerces an empty string) and
            // punctuation-only senders normalize to "" - keying on those would merge strangers.
            String sender = normalizeSender(m.sender());
            if (sender.isEmpty()) continue;
            return sender + ' ' + date;
        }
        return null;
    }

    static String normalizeSender(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[.,\\-–/]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    // TODO(Task 4): replaced by the real same-sender + same-issue-date merge.
    private static List<DocGroup> merge(List<DocGroup> groups, Map<Integer, PageMetadata> meta) {
        return new ArrayList<>(groups);
    }
}
