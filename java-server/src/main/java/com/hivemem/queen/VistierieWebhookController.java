package com.hivemem.queen;

import com.hivemem.queen.dto.CompletionPayload;
import com.hivemem.queen.dto.ToolCallRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * Inbound surface Vistierie calls back into: read-only tool webhooks for the Queen/Bee/Archivist,
 * plus two guarded WRITE webhooks for the Archivist (reclassify_cell, skip_inbox_cell), and the
 * Queen's completion webhook. Path is exempted from {@code AuthFilter}; this controller does its
 * own constant-time bearer-token check against the configured webhook tokens.
 */
@RestController
@RequestMapping("/vistierie")
public class VistierieWebhookController {

    private static final Logger log = LoggerFactory.getLogger(VistierieWebhookController.class);
    private static final String BEARER = "Bearer ";

    private final QueenProperties props;
    private final QueenWebhookService service;
    private final ObjectProvider<com.hivemem.consumption.SeparationApplier> separationApplier;
    private final ObjectProvider<com.hivemem.contradiction.ContradictionService> contradictionService;

    public VistierieWebhookController(QueenProperties props, QueenWebhookService service,
            ObjectProvider<com.hivemem.consumption.SeparationApplier> separationApplier,
            ObjectProvider<com.hivemem.contradiction.ContradictionService> contradictionService) {
        this.props = props;
        this.service = service;
        this.separationApplier = separationApplier;
        this.contradictionService = contradictionService;
    }

    @PostMapping("/tools/find_isolated_cells")
    public ResponseEntity<Map<String, Object>> findIsolatedCells(
            @RequestHeader(name = "Authorization", required = false) String auth,
            @RequestBody ToolCallRequest req) {
        requireToken(auth, props.getWebhookToken());
        int limit = intInput(req, "limit", props.getIsolatedBatchLimit());
        return output(service.findIsolatedCells(limit));
    }

    @PostMapping("/tools/read_cell")
    public ResponseEntity<Map<String, Object>> readCell(
            @RequestHeader(name = "Authorization", required = false) String auth,
            @RequestBody ToolCallRequest req) {
        requireToken(auth, props.getWebhookToken());
        try {
            return output(service.readCell(stringInput(req, "cell_id")));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid cell_id");
        }
    }

    @PostMapping("/tools/find_inbox_cells")
    public ResponseEntity<Map<String, Object>> findInboxCells(
            @RequestHeader(name = "Authorization", required = false) String auth,
            @RequestBody ToolCallRequest req) {
        requireToken(auth, props.getWebhookToken());
        int limit = intInput(req, "limit", props.getInboxBatchLimit());
        return output(service.findInboxCells(limit));
    }

    @PostMapping("/tools/list_taxonomy")
    public ResponseEntity<Map<String, Object>> listTaxonomy(
            @RequestHeader(name = "Authorization", required = false) String auth,
            @RequestBody(required = false) ToolCallRequest req) {
        requireToken(auth, props.getWebhookToken());
        return output(service.listTaxonomy());
    }

    @PostMapping("/tools/reclassify_cell")
    public ResponseEntity<Map<String, Object>> reclassifyCell(
            @RequestHeader(name = "Authorization", required = false) String auth,
            @RequestBody ToolCallRequest req) {
        requireToken(auth, props.getWebhookToken());
        try {
            return output(service.reclassifyInboxCell(
                    stringInput(req, "cell_id"),
                    optInput(req, "realm"), optInput(req, "signal"),
                    optInput(req, "topic"), stringInput(req, "reason")));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/tools/skip_inbox_cell")
    public ResponseEntity<Map<String, Object>> skipInboxCell(
            @RequestHeader(name = "Authorization", required = false) String auth,
            @RequestBody ToolCallRequest req) {
        requireToken(auth, props.getWebhookToken());
        try {
            return output(service.skipInboxCell(stringInput(req, "cell_id"), stringInput(req, "reason")));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/tools/search_similar_cells")
    public ResponseEntity<Map<String, Object>> searchSimilarCells(
            @RequestHeader(name = "Authorization", required = false) String auth,
            @RequestBody ToolCallRequest req) {
        requireToken(auth, props.getWebhookToken());
        try {
            return output(service.searchSimilarCells(stringInput(req, "cell_id"), intInput(req, "limit", 5)));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid cell_id");
        }
    }

    @PostMapping("/runs/done")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Void> completion(
            @RequestHeader(name = "Authorization", required = false) String auth,
            @RequestBody CompletionPayload payload) {
        requireToken(auth, props.getCompletionWebhookToken());
        if (payload != null && "done".equals(payload.status()) && payload.output() != null) {
            Object raw = payload.output().get("proposals");
            List<Map<String, Object>> proposals = raw instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
            int written = service.ingestProposals(proposals);
            log.info("Queen run {} ingested {} pending tunnel proposal(s)", payload.run_id(), written);
            return ResponseEntity.ok().build();
        }
        // Vistierie never attaches output to a failed run (see QueenWebhookService#
        // recoverProposalsFromChildRuns javadoc), so ANY failed Queen run would otherwise
        // discard every proposal the already-finished Bees produced — not just a tripped
        // max_run_seconds. Prod evidence (2026-08-09 review): the dominant failure text is
        // actually "tool_error: subagent_failed: ..." (one Bee's own failure, e.g. its own
        // max_run_seconds, kills the whole parent run via AgentRunner's tool_error path), not
        // the literal "max_run_seconds_exceeded" string — matching on that exact text alone
        // covered a small minority of failures and left the dominant class unrecovered. Recover
        // on every failed status instead; recoverProposalsFromChildRuns's own filters (bee
        // agent name, this run's parent_run_id, child status=done, output present) already make
        // this a safe no-op when there is nothing to recover (e.g. a Queen run that failed
        // before dispatching any Bee).
        if (payload != null && "failed".equals(payload.status())) {
            int written = service.recoverProposalsFromChildRuns(payload.run_id(), payload.started_at());
            log.info("Queen run {} failed ({}); recovered {} pending tunnel proposal(s) from "
                    + "already-finished bees", payload.run_id(), payload.error(), written);
            return ResponseEntity.ok().build();
        }
        log.info("Queen run {} status={} — nothing to ingest",
                payload == null ? "?" : payload.run_id(),
                payload == null ? "?" : payload.status());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/separation/done")
    public ResponseEntity<Void> separationDone(
            @RequestHeader(name = "Authorization", required = false) String auth,
            @RequestBody com.hivemem.consumption.SeparationResult payload) {
        requireToken(auth, props.getSeparationWebhookToken());
        if (payload == null || payload.runId() == null) {
            return ResponseEntity.badRequest().build();
        }
        com.hivemem.consumption.SeparationApplier applier = separationApplier.getIfAvailable();
        if (applier == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        applier.apply(payload);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/contradiction/done")
    public ResponseEntity<Void> contradictionDone(
            @RequestHeader(name = "Authorization", required = false) String auth,
            @RequestBody com.hivemem.contradiction.PairVerdicts payload) {
        requireToken(auth, props.getContradictionWebhookToken());
        var service = contradictionService.getIfAvailable();
        if (service == null) {
            // 200, not 503: a 5xx only makes Vistierie retry into the same wall.
            log.warn("Contradiction pair verdicts arrived while the feature is disabled; discarding");
            return ResponseEntity.ok().build();
        }
        if (payload == null || payload.run_id() == null) return ResponseEntity.badRequest().build();
        service.applyPairVerdicts(payload.run_id(), payload.status(),
                payload.output() == null ? null : payload.output().verdicts());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/cardinality/done")
    public ResponseEntity<Void> cardinalityDone(
            @RequestHeader(name = "Authorization", required = false) String auth,
            @RequestBody com.hivemem.contradiction.CardinalityVerdicts payload) {
        requireToken(auth, props.getContradictionWebhookToken());
        var service = contradictionService.getIfAvailable();
        if (service == null) {
            // Same 200-not-503 rationale as contradictionDone above.
            log.warn("Cardinality verdicts arrived while the feature is disabled; discarding");
            return ResponseEntity.ok().build();
        }
        if (payload == null || payload.run_id() == null) return ResponseEntity.badRequest().build();
        service.applyCardinalityVerdicts(payload.run_id(), payload.status(),
                payload.output() == null ? null : payload.output().verdicts());
        return ResponseEntity.ok().build();
    }

    private static ResponseEntity<Map<String, Object>> output(Object value) {
        return ResponseEntity.ok(Map.of("output", value));
    }

    private void requireToken(String authHeader, String expected) {
        if (!props.isEnabled() || expected == null || expected.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (authHeader == null || !authHeader.startsWith(BEARER)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        String presented = authHeader.substring(BEARER.length()).trim();
        if (!MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    private static String stringInput(ToolCallRequest req, String key) {
        Object v = req == null || req.input() == null ? null : req.input().get(key);
        if (v == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing " + key);
        return String.valueOf(v);
    }

    private static int intInput(ToolCallRequest req, String key, int fallback) {
        Object v = req == null || req.input() == null ? null : req.input().get(key);
        if (v instanceof Number n) return n.intValue();
        return fallback;
    }

    private static String optInput(ToolCallRequest req, String key) {
        Object v = req == null || req.input() == null ? null : req.input().get(key);
        return v == null ? null : String.valueOf(v);
    }
}
