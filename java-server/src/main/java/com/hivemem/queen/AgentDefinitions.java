package com.hivemem.queen;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Versioned, as-code definitions for the Queen + isolated-cell-Bee agents, built as
 * the JSON bodies Vistierie's POST/PUT /agents expects. Pure functions of QueenProperties.
 */
public class AgentDefinitions {

    public static final String BEE_NAME = "isolated-cell-bee";
    public static final String QUEEN_NAME = "queen";
    public static final String SEPARATOR_NAME = "document-separator";
    public static final String ARCHIVIST_NAME = "inbox-archivist";
    public static final String CONTRADICTION_JUDGE_NAME = "contradiction-judge";
    public static final String CARDINALITY_JUDGE_NAME = "predicate-cardinality-judge";

    private static final String BEE_SYSTEM = """
            You are an isolated-cell Bee in HiveMem, a personal knowledge graph.
            You receive ONE cell that currently has no links (tunnels) to other cells.
            Your job: read it, search for semantically similar cells, and propose only
            GENUINE relationships to other cells. Relations must be one of:
            related_to, builds_on, contradicts, refines.

            Steps:
            1. Call read_cell with the given cell_id to read its content.
            2. Call search_similar_cells to get candidate neighbours.
            3. For each candidate that is truly related, add a proposal with the best-fitting
               relation and a one-sentence note explaining the link. Skip weak/uncertain matches.

            Prefer proposing nothing over proposing noise.

            Return ONLY a raw JSON object matching the output schema — no prose, no explanation,
            no markdown, no headings, no ``` code fences. The response must start with `{`.
            Fields:
            - `cell_id`: echo the cell_id you were given, verbatim
            - `proposals`: array (possibly empty), each entry:
              - `to_cell`: the id of the related cell
              - `relation`: exactly one of `related_to`, `builds_on`, `contradicts`, `refines`
              - `note`: one sentence explaining the link (optional)
            An empty `proposals` array is a valid, expected result — return {"cell_id": "<id>", "proposals": []}
            rather than prose saying you found nothing.
            """;

    private static final String QUEEN_SYSTEM = """
            You are the Queen of a HiveMem knowledge hive. On each run:
            1. Call find_isolated_cells to get cells that have no links yet.
            2. For each returned cell_id, call dispatch_bee with input {"cell_id": "<id>"}.
            3. Collect every Bee's proposals. For each proposal, set from_cell to the Bee's
               input cell_id and copy to_cell, relation, note from the Bee output.
            Return ONLY a raw JSON object matching the output schema — no prose, no explanation,
            no markdown, no headings, no ``` code fences. The response must start with `{`.
            Fields:
            - `proposals`: array (possibly empty) of every proposal collected from all Bees, each entry:
              - `from_cell`: the cell_id you passed to that Bee
              - `to_cell`: copied from the Bee's proposal
              - `relation`: copied from the Bee's proposal (`related_to`, `builds_on`, `contradicts`, `refines`)
              - `note`: copied from the Bee's proposal (optional)
            - `surveyed`: integer — how many cells you surveyed this run
            Surveying zero cells is a valid result — return {"proposals": [], "surveyed": 0} rather than prose.
            """;

    private static final String ARCHIVIST_SYSTEM = """
            You are the HiveMem inbox archivist. Each run, call find_inbox_cells to get cells in the
            inbox staging realm that are ready to file. For each cell: read_cell for its full content
            and summary, and call list_taxonomy once to see existing realms/topics with counts.
            Decide realm, signal and topic:
            - Prefer an existing realm/topic that fits; only invent a new one when nothing fits.
            - signal MUST be exactly one of: facts, events, discoveries, preferences, advice.
            - Never file into the 'inbox' realm.
            Then call reclassify_cell with a one-sentence reason (what it is + why that filing).
            If a cell's content is empty, unreadable or genuinely ambiguous, do NOT guess — call
            skip_inbox_cell with a short reason; it leaves the inbox backlog so you won't re-see it.
            """;

    private static final String CONTRADICTION_JUDGE_SYSTEM = """
            You are the HiveMem contradiction judge. You are given a batch of candidate pairs of
            facts. Within each pair, both facts already share the same subject and the same
            predicate, and that predicate has already been determined (elsewhere, not by you) to
            be single-valued — meaning a subject can genuinely hold at most one true object for
            it at any one time.

            Your ONLY question, for each pair, is: do object_a and object_b denote the SAME
            real-world thing, or DIFFERENT things?
            - "München" vs "Munich" — same city, different spelling/language — SAME thing, not a
              contradiction.
            - "Berlin" vs "Munich" — different cities — DIFFERENT things, a genuine contradiction.
            - "2026-01-05" vs "5 Jan 2026" — same calendar date, different formatting — SAME
              thing, not a contradiction.
            - "42" vs "17" — different numbers — DIFFERENT things, a genuine contradiction.
            Apply the same reasoning to any predicate: judge whether the two objects could both
            be honestly restating the same fact (synonyms, translations, formatting/unit
            differences, abbreviations, different levels of precision) versus actually disagreeing.

            You are NOT told which fact is more up to date or currently correct, and you must NOT
            attempt to decide that — dates and provenance are deliberately withheld from you for
            exactly this reason. Deciding which fact wins is handled separately, in code, after
            your verdict. Your job stops at: same thing, or different things.

            Answer for EVERY pair in the batch, referencing it by its given `pair_id`. Return ONLY
            a raw JSON object matching the output schema — no prose, no explanation, no markdown,
            no code fences. The response must start with `{`.
            Fields:
            - `verdicts`: array, one entry per pair you were given:
              - `pair_id`: echoed verbatim from the input
              - `contradiction`: true if object_a and object_b denote different things, false if
                they denote the same thing
              - `confidence`: your certainty in this verdict, 0.0-1.0
              - `rationale`: one short sentence explaining the verdict (optional)
            Never omit a pair. Never add prose outside the JSON object.
            """;

    private static final String CARDINALITY_JUDGE_SYSTEM = """
            You are the HiveMem predicate-cardinality judge. You are given a batch of predicates
            used in a personal knowledge graph. For each predicate, decide whether it is
            single-valued or multi-valued, based purely on the SEMANTICS of the predicate name.

            - single_valued: a subject can honestly have at most one true object for this
              predicate at any one time. Examples: `date_of_birth`, `capital_city`,
              `current_employer`, `document_date`.
            - multi_valued: a subject can legitimately hold many simultaneous true objects for
              this predicate. Examples: `key_term`, `has_tag`, `likes`, `worked_on`, `mentions`.

            The payload includes `sample_objects` — a handful of actual object values recorded for
            this predicate. You MAY use these as legitimate semantic evidence: they can help you
            understand what kind of value the predicate holds (a date, a name, a free-form term,
            and so on) and confirm your reading of the predicate name. What you must NOT do is
            judge cardinality by HOW MANY objects are recorded. A HIGH object count is NOT evidence
            that a predicate is multi-valued — a subject holding many objects for a predicate that
            is semantically single-valued is precisely the kind of inconsistency this system exists
            to detect. Judging by count instead of semantics would permanently hide the very
            contradictions this feature looks for. Decide from what the predicate name (and the
            kind of values it holds) means, not from how often it appears.

            Answer for EVERY predicate in the batch. Return ONLY a raw JSON object matching the
            output schema — no prose, no explanation, no markdown, no code fences. The response
            must start with `{`.
            Fields:
            - `verdicts`: array, one entry per predicate you were given:
              - `predicate`: echoed verbatim from the input
              - `cardinality`: exactly one of `single_valued`, `multi_valued`
              - `confidence`: your certainty in this verdict, 0.0-1.0
              - `rationale`: one short sentence explaining the verdict, referencing the semantics
                you used (optional)
            Never omit a predicate. Never add prose outside the JSON object.
            """;

    private final QueenProperties props;

    public AgentDefinitions(QueenProperties props) {
        this.props = props;
    }

    private String toolUrl(String tool) {
        return props.getHivememBaseUrl() + "/vistierie/tools/" + tool;
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private static Map<String, Object> stringProp() {
        return Map.of("type", "string");
    }

    private Map<String, Object> httpTool(String name, String description, Map<String, Object> inputSchema) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name);
        t.put("description", description);
        t.put("input_schema", inputSchema);
        t.put("webhook_url", toolUrl(name));
        t.put("webhook_timeout_seconds", 10);
        return t;
    }

    public Map<String, Object> isolatedCellBee() {
        Map<String, Object> readCellIn = objectSchema(
                Map.of("cell_id", stringProp()), List.of("cell_id"));
        Map<String, Object> searchIn = objectSchema(
                Map.of("cell_id", stringProp(), "limit", Map.of("type", "integer")),
                List.of("cell_id"));

        Map<String, Object> proposalItem = objectSchema(
                Map.of(
                        "to_cell", stringProp(),
                        "relation", Map.of("type", "string",
                                "enum", List.of("related_to", "builds_on", "contradicts", "refines")),
                        "note", stringProp()),
                List.of("to_cell", "relation"));
        Map<String, Object> outputSchema = objectSchema(
                Map.of(
                        "cell_id", stringProp(),
                        "proposals", Map.of("type", "array", "items", proposalItem)),
                List.of("cell_id", "proposals"));

        Map<String, Object> def = new LinkedHashMap<>();
        def.put("name", BEE_NAME);
        def.put("system_prompt", BEE_SYSTEM);
        def.put("model_purpose", "bee_link");
        def.put("tools", List.of(
                httpTool("read_cell", "Read a HiveMem cell by id", readCellIn),
                httpTool("search_similar_cells", "Find cells semantically similar to a cell", searchIn)));
        def.put("output_schema", outputSchema);
        def.put("max_turns", 10);
        def.put("max_run_seconds", 60);
        def.put("webhook_token", props.getWebhookToken());
        return def;
    }

    public Map<String, Object> documentSeparator() {
        Map<String, Object> boundaryItem = objectSchema(
                Map.of(
                        "afterPage", Map.of("type", "integer"),
                        "confidence", Map.of("type", "number")),
                List.of("afterPage", "confidence"));
        Map<String, Object> outputSchema = objectSchema(
                Map.of("boundaries", Map.of("type", "array", "items", boundaryItem)),
                List.of("boundaries"));

        Map<String, Object> def = new LinkedHashMap<>();
        def.put("name", SEPARATOR_NAME);
        def.put("system_prompt", """
                You separate a scanned page stream into individual documents.
                You receive an ordered list of pages; each page has: page (1-based),
                head (first ~300 chars of OCR text), tail (last ~100 chars), blank (bool),
                hasPageMarker (a 'Seite X von Y' / 'Page X of Y' phrase was found).
                Decide AFTER which pages a new document begins. Use letterhead/sender changes,
                salutations, totals/signatures at page end, date jumps, blank separator pages,
                and 'Seite X von Y' counters. A blank page usually ends the previous document.
                Return STRICT JSON: {"boundaries":[{"afterPage":N,"confidence":0.0-1.0}, ...]}.
                confidence is YOUR certainty that a new document truly starts after page N.
                Prefer low confidence over dropping an uncertain boundary.
                If the whole stream is one document, return {"boundaries":[]}.
                """);
        def.put("model_purpose", "separator");
        def.put("tools", List.of());   // required by Vistierie CreateAgentRequest (@NotNull); separator needs none
        def.put("output_schema", outputSchema);
        def.put("max_turns", 5);
        def.put("max_run_seconds", 60);
        def.put("webhook_token", props.getWebhookToken());
        return def;
    }

    public Map<String, Object> queen() {
        Map<String, Object> findIn = objectSchema(
                Map.of("limit", Map.of("type", "integer")), List.of());

        Map<String, Object> dispatch = new LinkedHashMap<>();
        dispatch.put("name", "dispatch_bee");
        dispatch.put("description", "Dispatch an isolated-cell Bee for one cell");
        dispatch.put("input_schema", objectSchema(Map.of("cell_id", stringProp()), List.of("cell_id")));
        dispatch.put("type", "subagent");
        dispatch.put("target_agent", BEE_NAME);

        Map<String, Object> proposalItem = objectSchema(
                Map.of(
                        "from_cell", stringProp(),
                        "to_cell", stringProp(),
                        "relation", Map.of("type", "string",
                                "enum", List.of("related_to", "builds_on", "contradicts", "refines")),
                        "note", stringProp()),
                List.of("from_cell", "to_cell", "relation"));
        Map<String, Object> outputSchema = objectSchema(
                Map.of(
                        "proposals", Map.of("type", "array", "items", proposalItem),
                        "surveyed", Map.of("type", "integer")),
                List.of("proposals", "surveyed"));

        Map<String, Object> def = new LinkedHashMap<>();
        def.put("name", QUEEN_NAME);
        def.put("system_prompt", QUEEN_SYSTEM);
        def.put("model_purpose", "queen_survey");
        def.put("tools", List.of(
                httpTool("find_isolated_cells", "List cells that have no tunnels yet", findIn),
                dispatch));
        def.put("output_schema", outputSchema);
        def.put("max_turns", 40);
        // 600s (10 min), raised from 300s on 2026-08-09: prod measured a 20-cell batch at
        // 256-280s (avg ~13.4s/bee, 2914-cell backlog, 20 = QueenProperties.isolatedBatchLimit).
        // 300s left under 40s of margin, so ordinary night-to-night bee-latency variance was
        // enough to trip max_run_seconds_exceeded on 12 of the last 14 runs (297-314s). 600s
        // gives a normal run >2x headroom (600-280=320s spare) and comfortably absorbs a
        // several-slow-bees night (e.g. 5 bees at their own 60s cap + 15 at the ~14s average =
        // 510s) without still being the unrealistic every-bee-maxes bound (20*60=1200s) that
        // would let a genuinely stuck run hang for 20 minutes before failing.
        def.put("max_run_seconds", 600);
        def.put("webhook_token", props.getWebhookToken());
        def.put("schedule", props.getSchedule());
        def.put("completion_webhook", props.getHivememBaseUrl() + "/vistierie/runs/done");
        def.put("completion_webhook_token", props.getCompletionWebhookToken());
        return def;
    }

    public Map<String, Object> inboxArchivist() {
        Map<String, Object> emptyIn = objectSchema(Map.of(), List.of());
        Map<String, Object> limitIn = objectSchema(
                Map.of("limit", Map.of("type", "integer")), List.of());
        Map<String, Object> readIn = objectSchema(Map.of("cell_id", stringProp()), List.of("cell_id"));
        Map<String, Object> reclassifyIn = objectSchema(
                Map.of("cell_id", stringProp(), "realm", stringProp(),
                        "signal", Map.of("type", "string",
                                "enum", List.of("facts", "events", "discoveries", "preferences", "advice")),
                        "topic", stringProp(), "reason", stringProp()),
                List.of("cell_id", "reason"));
        Map<String, Object> skipIn = objectSchema(
                Map.of("cell_id", stringProp(), "reason", stringProp()),
                List.of("cell_id", "reason"));
        Map<String, Object> outputSchema = objectSchema(
                Map.of("classified", Map.of("type", "integer"),
                        "skipped", Map.of("type", "integer"),
                        "notes", stringProp()),
                List.of());

        Map<String, Object> def = new LinkedHashMap<>();
        def.put("name", ARCHIVIST_NAME);
        def.put("system_prompt", ARCHIVIST_SYSTEM);
        def.put("model_purpose", "archivist");
        def.put("tools", List.of(
                httpTool("find_inbox_cells", "List inbox cells ready to classify", limitIn),
                httpTool("read_cell", "Read a HiveMem cell by id", readIn),
                httpTool("list_taxonomy", "List existing realms/topics (with counts) and the fixed signals", emptyIn),
                httpTool("reclassify_cell", "Move an inbox cell to a realm/signal/topic with a reason", reclassifyIn),
                httpTool("skip_inbox_cell", "Mark an inbox cell as not-classifiable with a reason", skipIn)));
        def.put("output_schema", outputSchema);
        def.put("max_turns", 20);
        def.put("max_run_seconds", 120);
        def.put("webhook_token", props.getWebhookToken());
        def.put("schedule", props.getArchivistSchedule());
        return def;
    }

    public Map<String, Object> contradictionJudge() {
        Map<String, Object> verdictItem = objectSchema(
                Map.of(
                        "pair_id", stringProp(),
                        "contradiction", Map.of("type", "boolean"),
                        "confidence", Map.of("type", "number"),
                        "rationale", stringProp()),
                List.of("pair_id", "contradiction", "confidence"));
        Map<String, Object> outputSchema = objectSchema(
                Map.of("verdicts", Map.of("type", "array", "items", verdictItem)),
                List.of("verdicts"));

        Map<String, Object> def = new LinkedHashMap<>();
        def.put("name", CONTRADICTION_JUDGE_NAME);
        def.put("system_prompt", CONTRADICTION_JUDGE_SYSTEM);
        def.put("model_purpose", "contradiction_judge");
        def.put("tools", List.of());
        def.put("output_schema", outputSchema);
        def.put("max_turns", 1);
        def.put("max_run_seconds", 120);
        def.put("webhook_token", props.getWebhookToken());
        def.put("completion_webhook", props.getHivememBaseUrl() + "/vistierie/contradiction/done");
        def.put("completion_webhook_token", props.getContradictionWebhookToken());
        return def;
    }

    public Map<String, Object> predicateCardinalityJudge() {
        Map<String, Object> verdictItem = objectSchema(
                Map.of(
                        "predicate", stringProp(),
                        "cardinality", Map.of("type", "string",
                                "enum", List.of("single_valued", "multi_valued")),
                        "confidence", Map.of("type", "number"),
                        "rationale", stringProp()),
                List.of("predicate", "cardinality", "confidence"));
        Map<String, Object> outputSchema = objectSchema(
                Map.of("verdicts", Map.of("type", "array", "items", verdictItem)),
                List.of("verdicts"));

        Map<String, Object> def = new LinkedHashMap<>();
        def.put("name", CARDINALITY_JUDGE_NAME);
        def.put("system_prompt", CARDINALITY_JUDGE_SYSTEM);
        def.put("model_purpose", "predicate_cardinality");
        def.put("tools", List.of());
        def.put("output_schema", outputSchema);
        def.put("max_turns", 1);
        def.put("max_run_seconds", 120);
        def.put("webhook_token", props.getWebhookToken());
        def.put("completion_webhook", props.getHivememBaseUrl() + "/vistierie/cardinality/done");
        def.put("completion_webhook_token", props.getContradictionWebhookToken());
        return def;
    }
}
