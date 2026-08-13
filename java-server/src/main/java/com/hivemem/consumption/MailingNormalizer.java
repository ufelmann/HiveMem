package com.hivemem.consumption;

import com.hivemem.consumption.PageMetadataExtractor.PageMetadata;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic post-processing of pass 3: enforces in Java what MailingAssembler.PROMPT can only
 *  ask for - mailings that share sender + issue date are merged unless their reference numbers are
 *  clearly different, and the pages of a single complete labelled document are ordered by their
 *  printed labels. Pure function, never throws: an exception escaping assemble() would degrade the
 *  whole batch to one pending document. */
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
     *  Returns a new list of the surviving groups - absorbed groups are left out of it and keep
     *  their now-duplicated page lists. The given {@code DocGroup}s themselves are mutated in
     *  place (DocGroup.pages is a mutable list). */
    public List<DocGroup> normalize(List<DocGroup> groups, List<PageMetadata> pages) {
        if (groups == null) return new ArrayList<>();
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

    /** The merge key of a group. A plain concatenated string would be lossy: a normalized sender is
     *  space-joined words, and a printed date can itself contain spaces (German letters print date
     *  lines like "Musterstadt, den 5. September 2025"), so two different (sender, date) pairs could
     *  collide on the same string. The record's generated equals/hashCode give a correct map key.
     *  Both components are normalized: the sender through normalizeSender, the date through
     *  normalizeDate, so two spellings of one calendar date cannot split a mailing. */
    record AnchorKey(String sender, String date) {}

    /** The page a group anchors on: the first non-blank page carrying a usable sender AND a usable
     *  issue date. Shared by {@link #anchorKey} and {@link #anchorReference} so both always describe
     *  the SAME page — a key from one page and a reference from another would compare nonsense. */
    private static PageMetadata anchorPage(DocGroup g, Map<Integer, PageMetadata> meta) {
        for (Integer p : g.pages) {
            PageMetadata m = meta.get(p);
            if (m == null || m.blank()) continue;
            if (m.sender() == null || m.date() == null) continue;
            String date = m.date().trim();
            // "Stand ..." is the print date of a generic enclosure (see PageMetadataExtractor.
            // PROMPT); it must never anchor a mailing. Trimmed and without the trailing space so
            // " Stand 01.01.2025" and "Stand: 01.01.2025" cannot slip through. Case-insensitive:
            // the prompt only asks the model for "Stand ", nothing enforces the casing it returns
            // (LABEL above is CASE_INSENSITIVE for the same reason).
            if (date.isEmpty() || date.regionMatches(true, 0, "Stand", 0, 5)) continue;
            // An unreadable letterhead arrives as "" (asString(null) coerces an empty string) and
            // punctuation-only senders normalize to "" - keying on those would merge strangers.
            if (normalizeSender(m.sender()).isEmpty()) continue;
            return m;
        }
        return null;
    }

    /** The merge key of a group, or null when nothing in it can anchor. `reference` is deliberately
     *  NOT part of the key: a differently-read Steuernummer is exactly what split the tax batch.
     *  The reference is consulted separately, and only to REFUSE a merge — see
     *  {@link #clearlyDifferent}. */
    static AnchorKey anchorKey(DocGroup g, Map<Integer, PageMetadata> meta) {
        PageMetadata m = anchorPage(g, meta);
        if (m == null) return null;
        return new AnchorKey(normalizeSender(m.sender()), normalizeDate(m.date().trim()));
    }

    /** The raw reference of the anchor page, or null when the group has no anchor. Raw on purpose:
     *  normalization belongs to {@link #clearlyDifferent}, which is the only consumer. */
    static String anchorReference(DocGroup g, Map<Integer, PageMetadata> meta) {
        PageMetadata m = anchorPage(g, meta);
        return m == null ? null : m.reference();
    }

    static String normalizeSender(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[.,\\-–/]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** Map of the character pairs OCR actually confuses on reference numbers, folded onto the digit
     *  so that "12/345/6789O" and "12/345/67890" compare equal. Folding onto the digit rather than
     *  the letter is arbitrary but must stay consistent — the comparison only cares that both
     *  spellings land on the same character. */
    private static char foldConfusable(char c) {
        return switch (c) {
            case 'O' -> '0';
            case 'I', 'L' -> '1';
            case 'S' -> '5';
            case 'B' -> '8';
            case 'Z' -> '2';
            case 'G' -> '6';
            default -> c;
        };
    }

    /** A reference number reduced to what a comparison may rely on: upper case, ASCII alphanumerics
     *  only, OCR confusables folded. Returns "" for null, blank and punctuation-only input — callers
     *  read "" as "no usable reference" and must not split on it.
     *
     *  <p>Note what this does NOT do: it does not strip a label prefix. "Service-Nr." survives as
     *  "5ERV1CENR" because letters are alphanumeric. Prefix handling belongs to
     *  {@link #clearlyDifferent}'s containment clause, not here — stripping it would need the
     *  letter/digit boundary that folding confusables destroys. */
    static String normalizeReference(String s) {
        if (s == null) return "";
        String upper = s.toUpperCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(upper.length());
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) out.append(foldConfusable(c));
        }
        return out.toString();
    }

    /** A reference shorter than this is not trusted as a substring: "471" occurs inside plenty of
     *  unrelated numbers, and treating that as "same reference" would merge two strangers. */
    private static final int CONTAINMENT_MIN_LENGTH = 4;

    /** Edit distance, two-row dynamic programming. No dependency and no recursion: reference numbers
     *  are short, and MailingNormalizer must not throw — a StackOverflowError here would degrade the
     *  whole batch. */
    static int levenshtein(String a, String b) {
        if (a.equals(b)) return 0;
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[b.length()];
    }

    /** Whether two reference numbers are different enough to keep two mailings apart.
     *
     *  <p>Deliberately asymmetric in its bias: it answers false whenever it cannot be sure. A wrongly
     *  merged document is visible when read and repairable; a wrongly split letter scatters its pages
     *  and tends to go unnoticed. Measured 2026-08-08: forcing the merge cost 8 of 247 documents from
     *  one insurer that sends several letters on one day.
     *
     *  <p>Three gates, in order. A missing or unusable reference never splits. Containment covers the
     *  same reference written with and without its label ("Service-Nr. 1000000.1"), which the edit
     *  distance alone would call different because the prefix survives normalization. Only then does
     *  distance decide, against a threshold that scales with length so a long number tolerates more
     *  OCR noise than a short one.
     *
     *  <p>Known limitation, accepted in the spec: a long label prefix inflates both strings and
     *  therefore the threshold, which can suppress a real split. That failure is a missed split — the
     *  safe direction. */
    static boolean clearlyDifferent(String a, String b) {
        String na = normalizeReference(a);
        String nb = normalizeReference(b);
        if (na.isEmpty() || nb.isEmpty()) return false;
        if (na.length() >= CONTAINMENT_MIN_LENGTH && nb.contains(na)) return false;
        if (nb.length() >= CONTAINMENT_MIN_LENGTH && na.contains(nb)) return false;
        int threshold = Math.max(1, (int) Math.ceil(0.25 * Math.max(na.length(), nb.length())));
        return levenshtein(na, nb) > threshold;
    }

    /** German month names as the OCR actually delivers them: with the umlaut, with the umlaut
     *  stripped, and in the "ae/oe/ue" transcription. Lower case; the lookup lower-cases too. */
    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("januar", 1), Map.entry("februar", 2),
            Map.entry("marz", 3), Map.entry("märz", 3), Map.entry("maerz", 3),
            Map.entry("april", 4), Map.entry("mai", 5), Map.entry("juni", 6),
            Map.entry("juli", 7), Map.entry("august", 8), Map.entry("september", 9),
            Map.entry("oktober", 10), Map.entry("november", 11), Map.entry("dezember", 12));

    /** {@code 13.09.2016}, {@code 13.9.2016} - day first, German convention. A slash separator is
     *  deliberately not accepted here; see the class javadoc below. */
    private static final Pattern NUMERIC_DMY =
            Pattern.compile("^(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})$");

    /** {@code 2016-09-13}. */
    private static final Pattern ISO_YMD =
            Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$");

    /** {@code 21. März 2016}, {@code 5. September 2025}, with or without the dot. */
    private static final Pattern GERMAN_LONG =
            Pattern.compile("^(\\d{1,2})\\.?\\s+([\\p{L}]+)\\s+(\\d{4})$");

    /** A calendar date as {@code yyyy-MM-dd} when the shape is recognised AND the components form
     *  a real date, otherwise a conservative canonicalisation (trimmed, lower case, whitespace
     *  collapsed) of the input.
     *
     *  <p>Why this exists: measured on prod 2026-08-07, one batch reported the same letter date as
     *  {@code 2016-09-13} on one page and {@code 13.09.2016} on the next. {@link #anchorKey} keyed
     *  on the raw string, so the two groups never merged and the scan produced 4 mailings instead
     *  of 2.
     *
     *  <p>Deliberately conservative in both directions. A two-digit year is NOT expanded - guessing
     *  the century could merge two strangers - and impossible components (day 32, month 13,
     *  31 February) fall back instead of rolling over into a neighbouring month, which is what
     *  {@code LocalDate.of} would refuse and a lenient parser would silently do. A slash separator
     *  (e.g. {@code 09/05/2025}) is likewise NOT accepted: day-first and month-first cannot be told
     *  apart from the digits alone, and a wrong merge fuses two strangers' letters. Never throws:
     *  {@link MailingNormalizer} is a pure function whose exception would degrade the whole batch. */
    static String normalizeDate(String s) {
        if (s == null) return "";
        String t = s.trim().replaceAll("\\s+", " ");
        if (t.isEmpty()) return "";
        try {
            Matcher m = ISO_YMD.matcher(t);
            if (m.matches()) {
                return iso(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)), t);
            }
            m = NUMERIC_DMY.matcher(t);
            if (m.matches()) {
                return iso(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(1)), t);
            }
            m = GERMAN_LONG.matcher(t);
            if (m.matches()) {
                Integer month = MONTHS.get(m.group(2).toLowerCase(Locale.ROOT));
                if (month != null) {
                    return iso(Integer.parseInt(m.group(3)), month,
                            Integer.parseInt(m.group(1)), t);
                }
            }
        } catch (RuntimeException e) {
            // Fall through to the canonicalisation below - never throw out of a pure function.
        }
        return t.toLowerCase(Locale.ROOT);
    }

    /** {@code yyyy-MM-dd} when the three components form a real calendar date, else the fallback. */
    private static String iso(int year, int month, int day, String fallback) {
        try {
            return LocalDate.of(year, month, day).toString();
        } catch (RuntimeException e) {
            return fallback.toLowerCase(Locale.ROOT);
        }
    }

    /** A mailing under an anchor key, plus the reference it anchored on WHEN IT ARRIVED.
     *  Captured rather than recomputed: absorb() inserts pages into the middle of a group, which
     *  can move anchorPage() onto a different page and change the reference — comparing against a
     *  reference the group acquired by absorption caused a demonstrated over-split. */
    private record Candidate(String reference, DocGroup group) {}

    /** Collapse groups sharing an anchor key into the first compatible one that carried it.
     *
     *  <p>One key can now hold SEVERAL mailings: one sender may send several letters on one day, and
     *  their reference numbers are what tells them apart. An incoming group joins the first group
     *  under its key whose anchor reference is not {@link #clearlyDifferent} from its own, and starts
     *  its own mailing when none is. With no references anywhere every list holds at most one group,
     *  which is exactly the behaviour this method had before.
     *
     *  <p>{@link #clearlyDifferent} is a similarity test, not an equivalence relation, so non-
     *  transitive triples exist (A close to B, B close to C, A far from C). First-match-wins over
     *  such a predicate makes the resulting partition depend on the arrival order of the groups —
     *  the same three references can end up as one document or two depending on which one is seen
     *  first. That bias runs towards merging, which is the safe direction for this feature. */
    private static List<DocGroup> merge(List<DocGroup> groups, Map<Integer, PageMetadata> meta) {
        List<DocGroup> out = new ArrayList<>();
        Map<AnchorKey, List<Candidate>> byKey = new HashMap<>();
        for (DocGroup g : groups) {
            AnchorKey key = anchorKey(g, meta);
            if (key == null) {
                out.add(g);
                continue;
            }
            List<Candidate> candidates = byKey.computeIfAbsent(key, k -> new ArrayList<>());
            String reference = anchorReference(g, meta);
            DocGroup target = null;
            for (Candidate candidate : candidates) {
                if (!clearlyDifferent(candidate.reference(), reference)) {
                    target = candidate.group();
                    break;
                }
            }
            if (target == null) {
                candidates.add(new Candidate(reference, g));
                out.add(g);
            } else {
                absorb(target, g, meta);
            }
        }
        return out;
    }

    /** Move every page of `incoming` into `target`: behind its own label family where that stays
     *  unambiguous, appended otherwise. minConfidence takes the minimum - a merged mailing should
     *  rather land in the pending review queue than be committed silently. */
    private static void absorb(DocGroup target, DocGroup incoming, Map<Integer, PageMetadata> meta) {
        target.minConfidence = Math.min(target.minConfidence, incoming.minConfidence);

        Map<Integer, List<Integer>> byTotal = new LinkedHashMap<>();
        for (Integer p : incoming.pages) {
            Label l = label(meta.get(p));
            if (l != null) byTotal.computeIfAbsent(l.total(), t -> new ArrayList<>()).add(p);
        }
        Set<Integer> unambiguous = new HashSet<>();
        for (Map.Entry<Integer, List<Integer>> e : byTotal.entrySet()) {
            if (isFamilyUnambiguous(target, meta, e.getKey(), e.getValue())) {
                unambiguous.add(e.getKey());
            }
        }
        List<Integer> appended = new ArrayList<>();
        for (Integer p : incoming.pages) {
            Label l = label(meta.get(p));
            if (l == null || !unambiguous.contains(l.total())) appended.add(p);
        }
        // Insert family by family, recomputing the point each time: an earlier insertion shifts
        // the indices of everything behind it. Every total here was already proven unambiguous
        // above, so the point is always found - no fallback to append here.
        for (Integer total : unambiguous) {
            target.pages.addAll(familyInsertPoint(target, meta, total), byTotal.get(total));
        }
        target.pages.addAll(appended);
    }

    /** Whether inserting `incoming` into the target's label family for `total` stays unambiguous: a
     *  number must not appear twice in the target family, among the incoming pages, or across the
     *  two - and the target must actually have such a family. False means append, i.e. today's
     *  behaviour - a wrong insertion would split a document that is intact today. */
    private static boolean isFamilyUnambiguous(DocGroup target, Map<Integer, PageMetadata> meta,
                                               int total, List<Integer> incoming) {
        Set<Integer> numbers = new HashSet<>();
        boolean found = false;
        for (Integer p : target.pages) {
            Label l = label(meta.get(p));
            if (l != null && l.total() == total) {
                found = true;
                if (!numbers.add(l.number())) return false;
            }
        }
        if (!found) return false;
        for (Integer p : incoming) {
            Label l = label(meta.get(p));
            if (l == null || !numbers.add(l.number())) return false;
        }
        return true;
    }

    /** Index just behind the target's label family for `total`. Only meaningful once
     *  isFamilyUnambiguous has confirmed the family exists; recomputed on every call because an
     *  earlier insertion shifts the indices of everything behind it. */
    private static int familyInsertPoint(DocGroup target, Map<Integer, PageMetadata> meta, int total) {
        int last = -1;
        for (int i = 0; i < target.pages.size(); i++) {
            Label l = label(meta.get(target.pages.get(i)));
            if (l != null && l.total() == total) last = i;
        }
        return last + 1;
    }
}
