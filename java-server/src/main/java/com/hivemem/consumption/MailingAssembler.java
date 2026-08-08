package com.hivemem.consumption;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 *  so the orchestrator's degrade-to-pending path takes over. */
public class MailingAssembler {

    private static final Logger log = LoggerFactory.getLogger(MailingAssembler.class);

    static final String PROMPT = """
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

            Group ALL pages into mailings. Reply with STRICT JSON only:
            [{"mailing":"<short id>","description":"<sender + what it is + letter date>",
              "confidence":<0.0-1.0>,
              "pages":[<global page numbers in reading order: letter first, then its
              continuation pages by printed page label, then enclosures; blank pages last>]}]
            Every page exactly once across all mailings.

            Additional hard rules:
            - Printed page-label continuity: pages of the SAME sender whose printed labels form one
              continuous sequence are ONE document; body dates never split such a sequence.
            - It is FORBIDDEN to output two mailings with the same sender and the same letter date —
              merge them into one.
            - Enclosures from an affiliated authority/organization (e.g. a Datenschutz notice of the
              state tax administration inside a Finanzamt mailing) belong to the main mailing.
            - Every page must appear exactly once — re-check your output against the page list before
              answering.""";

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
        String prompt = PROMPT.formatted(rows.toString().strip());
        List<List<DocGroup>> drawn = new ArrayList<>();
        RuntimeException lastException = null;
        for (int draw = 1; draw <= draws; draw++) {
            try {
                drawn.add(parseDraw(realm, prompt, draw));
            } catch (RuntimeException e) {
                lastException = e;
            }
        }
        if (drawn.isEmpty()) {
            throw lastException;
        }
        List<DocGroup> groups;
        if (drawn.size() == 1) {
            groups = drawn.get(0);
        } else {
            List<Integer> pageNumbers = new ArrayList<>();
            for (PageMetadataExtractor.PageMetadata m : pages) pageNumbers.add(m.page());
            groups = consensus(drawn, pageNumbers);
            int[] sizes = drawn.stream().mapToInt(List::size).toArray();
            if (Arrays.stream(sizes).distinct().count() > 1) {
                log.info("Assembly draws disagreed ({} groups) — consensus over {} draw(s): {} groups",
                        Arrays.toString(sizes), drawn.size(), groups.size());
            }
        }
        return normalizer.normalize(groups, pages);
    }

    /** One grouping draw, with the same two attempts the single-draw path always had. */
    private List<DocGroup> parseDraw(String realm, String prompt, int draw) {
        JsonNode arr = null;
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                arr = LlmJson.parseArray(client.complete(realm, prompt));
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
     *  given draws — groups are ordered by their lowest page, pages ascending within a group.
     *
     *  <p>Measured 2026-08-08: the same scan yielded 5 mailings in one run and 1 in the next while
     *  its page metadata stayed identical, i.e. the instability sits in this grouping step. */
    static List<DocGroup> consensus(List<List<DocGroup>> draws, List<Integer> pageNumbers) {
        List<Integer> pages = new ArrayList<>(new LinkedHashSet<>(pageNumbers));
        if (pages.isEmpty()) {
            return new ArrayList<>();
        }
        int threshold = draws.size() / 2 + 1;

        // page -> group index, per draw. A page absent from a draw simply has no entry there.
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

        Map<Integer, Integer> parent = new HashMap<>();
        for (Integer p : pages) {
            parent.put(p, p);
        }
        for (int i = 0; i < pages.size(); i++) {
            for (int j = i + 1; j < pages.size(); j++) {
                int a = pages.get(i);
                int b = pages.get(j);
                int votes = 0;
                for (Map<Integer, Integer> byPage : assignment) {
                    Integer ga = byPage.get(a);
                    Integer gb = byPage.get(b);
                    if (ga != null && ga.equals(gb)) {
                        votes++;
                    }
                }
                if (votes >= threshold) {
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
            DocGroup group = new DocGroup("doc-" + (++n), describe(draws, assignment, component));
            group.pages.addAll(component);
            group.minConfidence = confidenceOf(draws, assignment, component);
            out.add(group);
        }
        return out;
    }

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

    /** The descriptor of the draw group that overlaps this component most; ties go to the earliest
     *  draw and lowest group index, so the result stays deterministic. Null when nothing overlaps. */
    private static String describe(List<List<DocGroup>> draws,
            List<Map<Integer, Integer>> assignment, List<Integer> component) {
        String best = null;
        int bestOverlap = 0;
        for (int d = 0; d < draws.size(); d++) {
            for (int i = 0; i < draws.get(d).size(); i++) {
                int overlap = 0;
                for (Integer p : component) {
                    Integer gi = assignment.get(d).get(p);
                    if (gi != null && gi == i) {
                        overlap++;
                    }
                }
                if (overlap > bestOverlap) {
                    bestOverlap = overlap;
                    best = draws.get(d).get(i).descriptor;
                }
            }
        }
        return best;
    }

    /** The lowest confidence among all draw groups that contributed a page to this component:
     *  a consensus built from disagreeing draws should rather land in the review queue. */
    private static double confidenceOf(List<List<DocGroup>> draws,
            List<Map<Integer, Integer>> assignment, List<Integer> component) {
        double min = Double.MAX_VALUE;
        for (int d = 0; d < draws.size(); d++) {
            for (Integer p : component) {
                Integer gi = assignment.get(d).get(p);
                if (gi != null) {
                    min = Math.min(min, draws.get(d).get(gi).minConfidence);
                }
            }
        }
        return min == Double.MAX_VALUE ? 0.0 : min;
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
