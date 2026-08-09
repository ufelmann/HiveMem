package com.hivemem.queen;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.cells.CellReadRepository;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.search.CellSearchRepository;
import com.hivemem.search.CellSearchRepository.RankedRow;
import com.hivemem.tools.read.CellFieldSelection;
import com.hivemem.write.WriteToolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Business logic behind the inbound Vistierie tool webhooks + completion-webhook ingest. */
@Service
public class QueenWebhookService {

    private static final Logger log = LoggerFactory.getLogger(QueenWebhookService.class);
    private static final Set<String> RELATIONS = Set.of("related_to", "builds_on", "contradicts", "refines");
    private static final AuthPrincipal QUEEN = new AuthPrincipal("queen", AuthRole.AGENT);
    private static final AuthPrincipal ARCHIVIST = new AuthPrincipal("inbox-archivist", AuthRole.AGENT);
    private static final List<String> SIGNALS = List.of("facts", "events", "discoveries", "preferences", "advice");

    private final QueenProperties props;
    private final QueenRepository repo;
    private final CellReadRepository cells;
    private final CellSearchRepository search;
    private final EmbeddingClient embedding;
    private final WriteToolService writes;
    private final VistierieRunsClient runsClient;

    public QueenWebhookService(QueenProperties props, QueenRepository repo, CellReadRepository cells,
                               CellSearchRepository search, EmbeddingClient embedding, WriteToolService writes,
                               VistierieRunsClient runsClient) {
        this.props = props;
        this.repo = repo;
        this.cells = cells;
        this.search = search;
        this.embedding = embedding;
        this.writes = writes;
        this.runsClient = runsClient;
    }

    public Map<String, Object> findIsolatedCells(int requestedLimit) {
        int limit = Math.min(Math.max(requestedLimit, 1), props.getIsolatedBatchLimit());
        List<String> ids = new ArrayList<>();
        for (UUID id : repo.findIsolatedCellIds(limit)) ids.add(id.toString());
        return Map.of("cell_ids", ids);
    }

    public Map<String, Object> readCell(String cellId) {
        Optional<Map<String, Object>> cell = cells.findCell(
                UUID.fromString(cellId),
                CellFieldSelection.forGetCell(List.of("content", "summary", "key_points", "insight")));
        return cell.orElseGet(Map::of);
    }

    public Map<String, Object> findInboxCells(int requestedLimit) {
        int limit = Math.min(Math.max(requestedLimit, 1), props.getInboxBatchLimit());
        List<String> ids = new ArrayList<>();
        for (UUID id : repo.findInboxCellIds(limit)) ids.add(id.toString());
        return Map.of("cell_ids", ids);
    }

    public Map<String, Object> listTaxonomy() {
        Map<String, Map<String, Object>> byRealm = new LinkedHashMap<>();
        for (Map<String, Object> row : repo.listTaxonomy()) {
            String realm = (String) row.get("realm");
            Map<String, Object> r = byRealm.computeIfAbsent(realm, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("realm", k);
                m.put("cell_count", 0L);
                m.put("topics", new ArrayList<Map<String, Object>>());
                return m;
            });
            long count = ((Number) row.get("cell_count")).longValue();
            r.put("cell_count", ((Number) r.get("cell_count")).longValue() + count);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> topics = (List<Map<String, Object>>) r.get("topics");
            Map<String, Object> topic = new LinkedHashMap<>();
            topic.put("topic", row.get("topic"));
            topic.put("cell_count", count);
            topics.add(topic);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("signals", SIGNALS);
        out.put("realms", new ArrayList<>(byRealm.values()));
        return out;
    }

    public Map<String, Object> reclassifyInboxCell(String cellId, String realm, String signal, String topic, String reason) {
        if (realm == null || realm.isBlank()) {
            throw new IllegalArgumentException("target realm is required");
        }
        if (realm.trim().equalsIgnoreCase("inbox")) {
            throw new IllegalArgumentException("cannot reclassify into the inbox staging realm");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
        return writes.reclassifyCell(ARCHIVIST, UUID.fromString(cellId), realm, topic, signal, reason);
    }

    public Map<String, Object> skipInboxCell(String cellId, String reason) {
        return writes.skipInboxCell(ARCHIVIST, UUID.fromString(cellId), reason);
    }

    public Map<String, Object> searchSimilarCells(String cellId, int requestedLimit) {
        UUID self = UUID.fromString(cellId);
        int limit = Math.min(Math.max(requestedLimit, 1), 10);

        Optional<Map<String, Object>> cell = cells.findCell(self,
                CellFieldSelection.forGetCell(List.of("content", "summary")));
        if (cell.isEmpty()) return Map.of("candidates", List.of());
        String text = String.valueOf(cell.get().getOrDefault("content", cell.get().get("summary")));

        List<Float> emb;
        try {
            emb = embedding.encodeQuery(text);
        } catch (RuntimeException e) {
            // Embedding service unavailable: fall back to keyword-only ranking (ranked_search
            // accepts a null query vector), mirroring ReadToolService.search.
            log.warn("Embedding unavailable for similar-cell search, using keyword-only fallback: {}", e.getMessage());
            emb = null;
        }
        List<RankedRow> rows = search.rankedSearch(emb, text, null, null, null, limit + 1,
                0.30, 0.15, 0.15, 0.15, 0.15, 0.10, null, null, null);

        List<Map<String, Object>> candidates = new ArrayList<>();
        for (RankedRow r : rows) {
            if (r.id().equals(self)) continue;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("cell_id", r.id().toString());
            c.put("summary", r.summary());
            c.put("realm", r.realm());
            c.put("score", r.scoreTotal());
            candidates.add(c);
            if (candidates.size() >= limit) break;
        }
        return Map.of("candidates", candidates);
    }

    /** Writes valid, non-duplicate proposals as pending tunnels. Returns count written. */
    public int ingestProposals(List<Map<String, Object>> proposals) {
        if (proposals == null) return 0;
        int written = 0;
        for (Object item : proposals) {
            try {
                if (!(item instanceof Map<?, ?> p)) {
                    log.warn("Queen proposal skipped: not an object: {}", item);
                    continue;
                }
                String relation = String.valueOf(p.get("relation"));
                if (!RELATIONS.contains(relation)) {
                    log.warn("Queen proposal skipped: bad relation '{}'", relation);
                    continue;
                }
                UUID from = UUID.fromString(String.valueOf(p.get("from_cell")));
                UUID to = UUID.fromString(String.valueOf(p.get("to_cell")));
                if (from.equals(to)) continue;
                if (repo.tunnelExists(from, to, relation)) continue;
                String note = p.get("note") == null ? null : String.valueOf(p.get("note"));
                writes.addTunnel(QUEEN, from, to, relation, note, "pending");
                written++;
            } catch (RuntimeException e) {
                log.warn("Queen proposal skipped: invalid payload {}", item, e);
            }
        }
        return written;
    }

    /**
     * Recovery for a Queen run that failed on {@code max_run_seconds_exceeded}. Vistierie's
     * {@code AgentRunner} passes {@code output=null} on every failure branch (there is no
     * partial-output delivery for the parent run itself), so the completion webhook body never
     * carries the proposals a timed-out Queen had already collected — even though most of that
     * work happened and would otherwise be lost.
     *
     * <p>Each {@code dispatch_bee} call is its own independent Vistierie run, though, and each
     * one is durably marked {@code done} with its own real output the moment that Bee finishes —
     * regardless of what happens to the parent Queen run afterwards. This method recovers that:
     * it lists the tenant's recent runs (the only shape that carries {@code parent_run_id} and
     * {@code output} — Vistierie's admin listing carries neither), keeps the {@code
     * isolated-cell-bee} children of {@code queenRunId} that reached {@code done}, and ingests
     * their proposals exactly as the Queen's own completion path would have (bee's own {@code
     * cell_id} becomes {@code from_cell}).
     *
     * <p>Best-effort: a Vistierie outage here must not throw back into the webhook handler — it
     * would just mean this specific night's timeout stays a total loss, same as before this
     * method existed.
     */
    public int recoverProposalsFromChildRuns(String queenRunId) {
        JsonNode body;
        try {
            body = runsClient.listRunsTenantScoped(100);
        } catch (RuntimeException e) {
            log.warn("Timeout recovery for queen run {}: could not list Vistierie runs: {}",
                    queenRunId, e.toString());
            return 0;
        }
        JsonNode items = body == null ? null : (body.has("items") ? body.get("items") : body);
        if (items == null || !items.isArray()) return 0;

        List<Map<String, Object>> mapped = new ArrayList<>();
        Set<String> surveyedCells = new LinkedHashSet<>();
        for (JsonNode r : items) {
            if (!AgentDefinitions.BEE_NAME.equals(text(r, "agent_name"))) continue;
            if (!queenRunId.equals(text(r, "parent_run_id"))) continue;
            if (!"done".equals(text(r, "status"))) continue;
            JsonNode output = r.get("output");
            if (output == null || output.isNull()) continue;
            String cellId = text(output, "cell_id");
            if (cellId == null) continue;
            surveyedCells.add(cellId);
            JsonNode proposals = output.get("proposals");
            if (proposals == null || !proposals.isArray()) continue;
            for (JsonNode p : proposals) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("from_cell", cellId);
                m.put("to_cell", text(p, "to_cell"));
                m.put("relation", text(p, "relation"));
                m.put("note", text(p, "note"));
                mapped.add(m);
            }
        }
        int written = ingestProposals(mapped);
        log.info("Timeout recovery for queen run {}: {} finished bee(s) recovered (of a requested "
                        + "batch), {} pending tunnel proposal(s) ingested",
                queenRunId, surveyedCells.size(), written);
        return written;
    }

    private static String text(JsonNode n, String field) {
        if (n == null) return null;
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }
}
