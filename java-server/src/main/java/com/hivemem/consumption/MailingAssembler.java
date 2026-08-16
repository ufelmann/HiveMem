package com.hivemem.consumption;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/** Pass 3 of the 3-pass reassembly: text-only assembly of per-page metadata into mailings.
 *  Grouping is a reasoning task over extracted facts, not a vision task — Haiku scored 5/5 here
 *  while every all-in-one vision variant failed. Sheet-pairing rule comes FIRST in the prompt;
 *  that wording is what fixed duplicate-enclosure assignment.
 *  The enclosure rule keys on the one field that actually reaches this pass: an enclosure is a
 *  page whose date is null or a "Stand" print date, never a letter date. Deliberately not "looks
 *  generic" and not "has no addressee" — the extractor emits no addressee at all, so a rule needing
 *  one would be unobservable here. Measured 2026-08-07: printed terms with their own form ID and
 *  page numbering were split off as their own mailing in four consecutive runs. The undated-page
 *  rule keeps the different-sender escape explicitly, because a degraded page also arrives with
 *  date=null and {@link MailingNormalizer} can merge but never split. Throws on unparseable output
 *  so the orchestrator's degrade-to-pending path takes over.
 *  The same-sender/same-date ban was relaxed on 2026-08-13: measured on the 2026-08-08 batch, one
 *  insurer sending several letters on one day cost 8 of 247 documents, because the blanket ban made
 *  the correct answer unavailable to the model. The reference number is the discriminator, and
 *  MailingNormalizer.clearlyDifferent decides when two of them count as different. */
public class MailingAssembler {

    private static final Logger log = LoggerFactory.getLogger(MailingAssembler.class);

    /** Shared grouping rules for both routes: the forced tool call ({@link #PROMPT}) and the text
     *  fallback ({@link #TEXT_PROMPT}). Ends right after the {@code Pages:} block — the {@code %s}
     *  placeholder is filled with the page rows by {@link #assemble}. Everything here is shared
     *  byte-for-byte between the two routes, since it is the wording that was measured against real
     *  batches (see class javadoc); only the closing output-format instruction (appended by
     *  {@link #PROMPT} / {@link #TEXT_PROMPT}) differs. */
    private static final String GROUPING_RULES_HEAD = """
            Below are per-page descriptions of ONE scanned batch (a stack of several
            separate letters was scanned front+back on a duplex scanner).

            Physical constraint of duplex scanning — APPLY THIS FIRST, before any content rule:
            consecutive page pairs form one physical sheet (pages 1+2 = sheet 1 front/back,
            3+4 = sheet 2, ...). Both pages of a sheet ALWAYS belong to the SAME mailing. Assign
            each sheet to a mailing based on whichever of its two pages is clearly identifiable
            (a letter, a data sheet with a contract number, a dated Bescheid); the sheet's other
            page — enclosure, generic notice (Datenschutz, Widerruf, terms), or blank — follows
            its sheet partner into that mailing. Enclosure pages and blanks are
            NEVER their own mailing. Only exception: the scanner may silently drop a fully blank
            back side, which shifts pairing for the pages AFTER the drop — if the pairing produces
            an impossible sheet (two pages that are clearly fronts of different senders' letters),
            re-anchor the pairing there.

            A MAILING = everything that arrived in one envelope: the letter itself PLUS its
            enclosures (data sheets, SEPA mandate, Datenschutz/privacy notice, Widerruf notice,
            contract terms).

            An ENCLOSURE is any sheet that does not itself open a piece of post: its date field is
            null, or carries only a "Stand ..." print date. This holds even when the sheet looks
            self-contained — printed terms and conditions, a Widerruf or Abtretung notice, a
            data-protection sheet or a form annex will normally carry their own title, their own
            form ID (e.g. "123 456.000 A1 (Fassung 1. Januar 2020)"), a publisher's imprint and
            even their own page numbering ("Seite 1 von 5"). None of that makes it a mailing. A
            sender's letterhead on such a sheet does not either — enclosures are printed on the
            sender's stationery. Enclosures also carry their own print dates ("Stand ...") — that
            does NOT make them separate mailings.

            An enclosure ALWAYS joins the mailing it was scanned adjacent to. It is NEVER its own
            mailing. A new mailing starts only where a page carries its OWN letter date — a bare
            date in the date field, never null and never a "Stand" print date — or where the sender
            is unmistakably a different one. Looking self-contained is never enough on its own: a
            page without a letter date does not open a mailing on the strength of its own title,
            form ID or page numbering. A clearly different sender still may, even undated.

            Two letters from the same sender with different LETTER dates are two different
            mailings; identical enclosure copies then belong to the mailing they were scanned
            adjacent to.

            Pages:
            %s

            """;

    /** Continuation of the shared grouping rules, resumed right after the closing output-format
     *  instruction that differs per route (see {@link #GROUPING_RULES_HEAD}). */
    private static final String GROUPING_RULES_TAIL = """


            Additional hard rules:
            - Printed page-label continuity: pages of the SAME sender whose printed labels form one
              continuous sequence are ONE document; body dates never split such a sequence.
            - Two mailings with the same sender AND the same letter date are allowed ONLY when they
              carry clearly different reference numbers (Aktenzeichen, Vertrags-, Service- or
              Kundennummer). One insurer's annual mailing typically looks exactly like this: several
              separate letters, one date, one reference number each. If the references are equal, or
              differ only in characters OCR commonly confuses (O/0, I/1, S/5, B/8), they are the same
              reference — merge those into one mailing.
            - An enclosure's own form ID (e.g. "123 456.000 A1") is NOT a reference number for this
              rule. Enclosures never open a mailing, whatever numbers they carry.
            - Enclosures from an affiliated authority/organization (e.g. a Datenschutz notice of the
              state tax administration inside a Finanzamt mailing) belong to the main mailing.
            - Every page must appear exactly once — re-check your output against the page list before
              answering.""";

    /** Tool-call route: forces the model to deliver the grouping as the {@code submit_mailings}
     *  tool call input instead of text. Name kept exactly {@code PROMPT} — {@link
     *  MailingAssemblerTest#promptAllowsSameSenderSameDateWhenReferencesDiffer} and other tests
     *  assert on it. */
    static final String PROMPT = GROUPING_RULES_HEAD + """
            Group ALL pages into mailings and deliver the result by calling the submit_mailings
            tool. Do not write the answer as text and do not explain your reasoning first.
            Every page exactly once across all mailings.""" + GROUPING_RULES_TAIL;

    /** Text-only fallback route, used when the tool call throws or returns an unusable shape. Same
     *  rules as {@link #PROMPT}, but instructs the model to answer with STRICT JSON in a message —
     *  there is no tool on this call for the model to invoke. Restored verbatim from before the
     *  tool call was added (git show 221c876:.../MailingAssembler.java). */
    static final String TEXT_PROMPT = GROUPING_RULES_HEAD + """
            Group ALL pages into mailings. Reply with STRICT JSON only:
            [{"mailing":"<short id>","description":"<sender + what it is + letter date>",
              "confidence":<0.0-1.0>,
              "pages":[<global page numbers in reading order: letter first, then its
              continuation pages by printed page label, then enclosures; blank pages last>]}]
            Every page exactly once across all mailings.""" + GROUPING_RULES_TAIL;

    static final String TOOL_NAME = "submit_mailings";

    private static final String TOOL_DESCRIPTION =
            "Deliver the final grouping of pages into mailings. Call this exactly once. "
            + "Pass the result as this tool's input — do not write it as text.";

    /** Mirrors the shape the text path parsed, so parseDraw's mapping is identical either way. */
    static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "required", List.of("mailings"),
            "properties", Map.of(
                    "mailings", Map.of(
                            "type", "array",
                            "items", Map.of(
                                    "type", "object",
                                    "required", List.of("mailing", "pages"),
                                    "properties", Map.of(
                                            "mailing", Map.of("type", "string",
                                                    "description", "short id"),
                                            "description", Map.of("type", "string",
                                                    "description",
                                                    "sender + what it is + letter date"),
                                            "confidence", Map.of("type", "number",
                                                    "minimum", 0, "maximum", 1),
                                            "pages", Map.of("type", "array",
                                                    "items", Map.of("type", "integer"),
                                                    "description",
                                                    "global page numbers in reading order: letter "
                                                    + "first, then its continuation pages by "
                                                    + "printed page label, then enclosures; blank "
                                                    + "pages last"))))));

    /** Batches at or below this size are left alone: a one-group answer is often simply right. */
    static final int DEGENERATE_MIN_PAGES = 20;
    /** Share of the batch in ONE group that marks a grouping as collapsed. Observed damage on
     *  2026-08-15 was 39 of 41 pages (95%) against five correct documents. */
    static final double DEGENERATE_SHARE = 0.90;

    private final CompleteClient client;
    private final int draws;
    private final MailingNormalizer normalizer = new MailingNormalizer();

    /** Single-draw assembler: today's behaviour exactly, no vote. Used by tests and by callers
     *  that do not want the extra draws. */
    public MailingAssembler(CompleteClient client) {
        this(client, 1);
    }

    /** @param draws how many independent groupings to draw before the pairwise-majority vote.
     *      Values below 1 are clamped to 1. */
    public MailingAssembler(CompleteClient client, int draws) {
        this.client = client;
        this.draws = Math.max(1, draws);
    }

    /** Assemble mailings from per-page metadata, then hand the model's grouping to
     *  {@link MailingNormalizer}: mailings sharing sender + issue date are merged and pages are
     *  ordered by their printed labels. minConfidence carries the mailing confidence (drives
     *  committed vs pending downstream). */
    public List<DocGroup> assemble(String realm, List<PageMetadataExtractor.PageMetadata> pages) {
        StringBuilder rows = new StringBuilder();
        for (PageMetadataExtractor.PageMetadata m : pages) {
            log.debug("Assembly input page {}: date={}, label={}, blank={}",
                    m.page(), m.date(), m.pageLabel(), m.blank());
            rows.append("- page ").append(m.page())
                    .append(": sender=").append(pyRepr(m.sender()))
                    .append(", date=").append(pyRepr(m.date()))
                    .append(", printed_page_label=").append(pyRepr(m.pageLabel()))
                    .append(", blank=").append(m.blank())
                    .append(", reference=").append(pyRepr(m.reference()))
                    .append(", content=").append(pyRepr(m.docType()))
                    .append(" - ").append(pyRepr(m.summary()))
                    .append('\n');
        }
        String rowsBlock = rows.toString().strip();
        String toolPrompt = PROMPT.formatted(rowsBlock);
        String textPrompt = TEXT_PROMPT.formatted(rowsBlock);
        List<List<DocGroup>> drawn = new ArrayList<>();
        RuntimeException lastException = null;
        for (int draw = 1; draw <= draws; draw++) {
            try {
                drawn.add(parseDraw(realm, toolPrompt, textPrompt, draw));
            } catch (RuntimeException e) {
                lastException = e;
            }
        }
        if (drawn.isEmpty()) {
            throw lastException;
        }
        if (drawn.size() < draws) {
            log.info("Assembly completed {} of {} requested draws; consensus uses only the {} that "
                    + "succeeded", drawn.size(), draws, drawn.size());
        }
        List<DocGroup> groups;
        if (drawn.size() == 1) {
            groups = drawn.get(0);
            // No vote ran, so nothing can overrule this draw. A collapsed grouping here is how a
            // 41-page scan overwrote five correct documents on 2026-08-15 — refuse it and let the
            // orchestrator degrade the batch to pending instead.
            if (isDegenerate(groups, pages.size())) {
                throw new IllegalStateException(
                        "Assembly rejected: single unvoted draw put " + largestGroup(groups)
                        + " of " + pages.size() + " pages in one mailing");
            }
        } else {
            List<Integer> pageNumbers = new ArrayList<>();
            for (PageMetadataExtractor.PageMetadata m : pages) pageNumbers.add(m.page());
            groups = consensus(drawn, pageNumbers);
            int[] sizes = drawn.stream().mapToInt(List::size).toArray();
            int contested = contestedPairs(drawn, pageNumbers);
            if (contested > 0) {
                log.info("Assembly draws disagreed on {} page pair(s) ({} groups) — consensus over "
                        + "{} draw(s): {} groups",
                        contested, Arrays.toString(sizes), drawn.size(), groups.size());
            }
        }
        return normalizer.normalize(groups, pages);
    }

    /** One grouping draw. Prefers the forced tool call; falls back to parsing text so a provider
     *  that returns no usable tool_use payload — or whose call itself throws (gateway 400/5xx,
     *  timeout) — degrades to the previous behaviour instead of failing the batch. The tool call
     *  has its own try/catch so a throw there falls through to the text route WITHIN the same
     *  attempt, rather than the outer catch burning the whole attempt on retrying the tool call
     *  again: otherwise, with the tool half absent or rolled back, every draw would fail where the
     *  old text-only code succeeded. Keeps the same two attempts the single-draw path always had. */
    private List<DocGroup> parseDraw(String realm, String toolPrompt, String textPrompt, int draw) {
        JsonNode arr = null;
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                JsonNode toolInput = null;
                try {
                    toolInput = client.completeWithTool(
                            realm, toolPrompt, TOOL_NAME, TOOL_DESCRIPTION, SCHEMA);
                } catch (RuntimeException e) {
                    log.warn("Assembly draw {} attempt {}/2: tool call failed, falling back to "
                            + "text: {}", draw, attempt, e.toString());
                }
                if (toolInput != null && toolInput.path("mailings").isArray()) {
                    arr = toolInput.path("mailings");
                    break;
                }
                if (toolInput == null) {
                    log.info("Assembly draw {} attempt {}/2: no tool_use payload, parsing text",
                            draw, attempt);
                } else {
                    log.info("Assembly draw {} attempt {}/2: tool_use payload had the wrong shape "
                            + "(mailings={}), parsing text", draw, attempt,
                            toolInput.path("mailings").getNodeType());
                }
                arr = LlmJson.parseArray(client.complete(realm, textPrompt));
                break;
            } catch (RuntimeException e) {
                log.warn("Assembly draw {} attempt {}/2 failed: {}", draw, attempt, e.toString());
                lastException = e;
            }
        }
        if (arr == null) {
            throw lastException;
        }
        List<DocGroup> groups = new ArrayList<>();
        for (JsonNode m : arr) {
            DocGroup g = new DocGroup(m.path("mailing").asString("doc-" + (groups.size() + 1)),
                    m.path("description").asString(null));
            for (JsonNode p : m.path("pages")) g.pages.add(p.asInt());
            g.minConfidence = m.path("confidence").asDouble(0.0);
            groups.add(g);
        }
        return groups;
    }

    /** True when a single group swallows nearly the whole batch. Only consulted for an UNVOTED
     *  draw — a long contract legitimately fills its batch, and rejecting those unconditionally
     *  would be a systematic false positive rather than a rare one. */
    static boolean isDegenerate(List<DocGroup> groups, int pageCount) {
        if (pageCount <= DEGENERATE_MIN_PAGES) return false;
        for (DocGroup g : groups) {
            if (g.pages.size() >= DEGENERATE_SHARE * pageCount) return true;
        }
        return false;
    }

    private static int largestGroup(List<DocGroup> groups) {
        int max = 0;
        for (DocGroup g : groups) max = Math.max(max, g.pages.size());
        return max;
    }

    /** Merge N independently drawn partitions of the same page set into one, by pairwise majority.
     *
     *  <p>For every unordered page pair, count the draws that put both pages in the SAME group; at
     *  a strict majority the pair is unioned. Union-find then resolves transitivity, so pages can
     *  end up together even when no single draw put them together — that is intended: the vote is
     *  over the relation "same mailing", not over whole partitions, which cannot be averaged.
     *
     *  <p>Why a strict majority ({@code draws / 2 + 1}) and not {@code ceil(draws / 2)}: at two
     *  draws the latter is 1, so a single draw's merge would win. Merging two strangers' letters is
     *  the expensive direction — {@link MailingNormalizer} can merge but never split — so two draws
     *  demand unanimity.
     *
     *  <p>The page universe is the caller's page list, never the draws: a page no draw mentioned
     *  still has to land somewhere, and becomes its own group.
     *
     *  <p>Pure and total: no LLM, no clock, no randomness, and it never throws. Deterministic for
     *  given draws — groups are ordered by their lowest page; the page order WITHIN a group follows
     *  the best-matching draw group (see {@link #findBest}), because that order is the model's
     *  reading order (letter, continuation pages, enclosures, blanks last) and survives into the
     *  produced sub-PDF — sorting it away would silently reshuffle every multi-draw mailing.
     *
     *  <p>Measured 2026-08-08: the same scan yielded 5 mailings in one run and 1 in the next while
     *  its page metadata stayed identical, i.e. the instability sits in this grouping step. */
    static List<DocGroup> consensus(List<List<DocGroup>> draws, List<Integer> pageNumbers) {
        List<Integer> pages = new ArrayList<>(new LinkedHashSet<>(pageNumbers));
        if (pages.isEmpty()) {
            return new ArrayList<>();
        }
        int threshold = draws.size() / 2 + 1;
        List<Map<Integer, Integer>> assignment = buildAssignment(draws);

        Map<Integer, Integer> parent = new HashMap<>();
        for (Integer p : pages) {
            parent.put(p, p);
        }
        Map<PagePair, Integer> votes = new HashMap<>();
        for (int i = 0; i < pages.size(); i++) {
            for (int j = i + 1; j < pages.size(); j++) {
                int a = pages.get(i);
                int b = pages.get(j);
                int v = countVotes(assignment, a, b);
                votes.put(new PagePair(a, b), v);
                if (v >= threshold) {
                    union(parent, a, b);
                }
            }
        }

        // Components, keyed by their representative, ordered by lowest page.
        Map<Integer, List<Integer>> components = new LinkedHashMap<>();
        List<Integer> sorted = new ArrayList<>(pages);
        sorted.sort(Comparator.naturalOrder());
        for (Integer p : sorted) {
            components.computeIfAbsent(find(parent, p), k -> new ArrayList<>()).add(p);
        }

        List<DocGroup> out = new ArrayList<>();
        int n = 0;
        for (List<Integer> component : components.values()) {
            BestMatch best = findBest(draws, assignment, component);
            DocGroup group = new DocGroup("doc-" + (++n), describe(draws, best));
            group.pages.addAll(orderComponent(draws, best, component));
            group.minConfidence = confidenceOf(draws, best, component, votes);
            out.add(group);
        }
        return out;
    }

    /** page -> group index, per draw. A page absent from a draw simply has no entry there. */
    private static List<Map<Integer, Integer>> buildAssignment(List<List<DocGroup>> draws) {
        List<Map<Integer, Integer>> assignment = new ArrayList<>();
        for (List<DocGroup> draw : draws) {
            Map<Integer, Integer> byPage = new HashMap<>();
            for (int i = 0; i < draw.size(); i++) {
                for (Integer p : draw.get(i).pages) {
                    byPage.putIfAbsent(p, i);
                }
            }
            assignment.add(byPage);
        }
        return assignment;
    }

    /** How many draws put pages {@code a} and {@code b} in the same group. */
    private static int countVotes(List<Map<Integer, Integer>> assignment, int a, int b) {
        int votes = 0;
        for (Map<Integer, Integer> byPage : assignment) {
            Integer ga = byPage.get(a);
            Integer gb = byPage.get(b);
            if (ga != null && ga.equals(gb)) {
                votes++;
            }
        }
        return votes;
    }

    /** Counts page pairs the draws disagreed on, i.e. where {@code 0 < votes < draws.size()}. Two
     *  draws can produce the SAME number of groups while disagreeing on every single pair (e.g.
     *  {1,2},{3,4} vs {1,3},{2,4}), so the group-count alone is not a fit metric for how much the
     *  vote had to decide — this is. */
    private static int contestedPairs(List<List<DocGroup>> draws, List<Integer> pageNumbers) {
        List<Integer> pages = new ArrayList<>(new LinkedHashSet<>(pageNumbers));
        List<Map<Integer, Integer>> assignment = buildAssignment(draws);
        int contested = 0;
        for (int i = 0; i < pages.size(); i++) {
            for (int j = i + 1; j < pages.size(); j++) {
                int votes = countVotes(assignment, pages.get(i), pages.get(j));
                if (votes > 0 && votes < draws.size()) {
                    contested++;
                }
            }
        }
        return contested;
    }

    /** Identifies the draw group that best represents a component, by a three-level rule:
     *  (1) strictly greater overlap (pages shared with the component) wins; (2) on equal overlap,
     *  strictly SMALLER group size wins — the tighter group is the one whose overlap with the
     *  component isn't diluted by unrelated pages, i.e. the one that actually corresponds to the
     *  consensus component rather than a superset of it; a superset group tying on overlap would
     *  otherwise donate its reading order and its confidence to a component it only partly
     *  describes; (3) only when both overlap and size are equal does the earliest draw index, then
     *  lowest group index within that draw, win — draw/group order carries no meaning on its own
     *  and exists purely to keep the result deterministic. This is the single source of truth
     *  {@link #describe}, the within-group page order and the base confidence all read from, so
     *  they can never disagree with each other. {@link BestMatch#NONE} when no draw group shares
     *  any page with the component. */
    private static BestMatch findBest(List<List<DocGroup>> draws,
            List<Map<Integer, Integer>> assignment, List<Integer> component) {
        BestMatch best = BestMatch.NONE;
        int bestOverlap = 0;
        int bestSize = Integer.MAX_VALUE;
        for (int d = 0; d < draws.size(); d++) {
            for (int i = 0; i < draws.get(d).size(); i++) {
                int overlap = 0;
                for (Integer p : component) {
                    Integer gi = assignment.get(d).get(p);
                    if (gi != null && gi == i) {
                        overlap++;
                    }
                }
                int size = draws.get(d).get(i).pages.size();
                if (overlap > bestOverlap || (overlap == bestOverlap && overlap > 0 && size < bestSize)) {
                    bestOverlap = overlap;
                    bestSize = size;
                    best = new BestMatch(d, i);
                }
            }
        }
        return best;
    }

    /** {@code (drawIndex, groupIndex)} of the draw group {@code findBest} picked; {@link #NONE}
     *  when nothing overlapped. */
    private record BestMatch(int drawIndex, int groupIndex) {
        static final BestMatch NONE = new BestMatch(-1, -1);
    }

    /** An unordered page pair, always stored with {@code a < b}. */
    private record PagePair(int a, int b) {}

    private static int find(Map<Integer, Integer> parent, int x) {
        int root = x;
        while (parent.get(root) != root) {
            root = parent.get(root);
        }
        for (int cur = x; cur != root; ) {
            int next = parent.get(cur);
            parent.put(cur, root);
            cur = next;
        }
        return root;
    }

    private static void union(Map<Integer, Integer> parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent.put(Math.max(ra, rb), Math.min(ra, rb));
        }
    }

    /** The descriptor of the {@link #findBest}-matching draw group; null when nothing overlapped. */
    private static String describe(List<List<DocGroup>> draws, BestMatch best) {
        return best.drawIndex() < 0 ? null : draws.get(best.drawIndex()).get(best.groupIndex()).descriptor;
    }

    /** Orders a component's pages the way the {@link #findBest}-matching draw group had them —
     *  that is the model's reading order (letter, continuation pages, enclosures, blanks last),
     *  and it must survive the vote unchanged since {@link PageReassembler} and (for a fully
     *  labelled family) {@link MailingNormalizer#order} both build on it. Pages the best-matching
     *  group does not mention (or when nothing overlapped at all) are appended ascending. */
    private static List<Integer> orderComponent(List<List<DocGroup>> draws, BestMatch best,
            List<Integer> component) {
        List<Integer> ordered = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        if (best.drawIndex() >= 0) {
            Set<Integer> componentSet = new HashSet<>(component);
            for (Integer p : draws.get(best.drawIndex()).get(best.groupIndex()).pages) {
                if (componentSet.contains(p) && used.add(p)) {
                    ordered.add(p);
                }
            }
        }
        List<Integer> remaining = new ArrayList<>();
        for (Integer p : component) {
            if (!used.contains(p)) {
                remaining.add(p);
            }
        }
        remaining.sort(Comparator.naturalOrder());
        ordered.addAll(remaining);
        return ordered;
    }

    /** {@code baseConfidence * agreement}: {@code baseConfidence} is the minConfidence of the
     *  {@link #findBest}-matching draw group (0.0 when nothing overlapped), and {@code agreement}
     *  is the mean, over every unordered page pair INSIDE the component, of {@code votes / draws}
     *  (1.0 for a single-page component, which has no pair to disagree on). A single draw that
     *  lumps unrelated pages into one low-confidence group therefore no longer stamps that low
     *  number onto every OTHER component it happens to touch — only the component it actually best
     *  matches is charged its confidence, and only to the extent the other draws agreed with it. */
    private static double confidenceOf(List<List<DocGroup>> draws, BestMatch best,
            List<Integer> component, Map<PagePair, Integer> votes) {
        double base = best.drawIndex() < 0 ? 0.0 : draws.get(best.drawIndex()).get(best.groupIndex()).minConfidence;
        if (component.size() <= 1) {
            return base;
        }
        double sum = 0;
        int pairs = 0;
        for (int i = 0; i < component.size(); i++) {
            for (int j = i + 1; j < component.size(); j++) {
                int a = component.get(i);
                int b = component.get(j);
                PagePair key = a < b ? new PagePair(a, b) : new PagePair(b, a);
                Integer v = votes.get(key);
                sum += (v == null ? 0 : v) / (double) draws.size();
                pairs++;
            }
        }
        double agreement = pairs == 0 ? 1.0 : sum / pairs;
        return base * agreement;
    }

    /** Render like Python's repr so the rows match the validated prompt format exactly:
     *  null → None, string → '...' (backslashes and single quotes inside the value escaped,
     *  newlines/carriage returns flattened to spaces so a multi-line summary can't break the
     *  one-row-per-page format). */
    private static String pyRepr(String s) {
        return s == null ? "None" : "'" + s.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\n", " ").replace("\r", " ") + "'";
    }
}
