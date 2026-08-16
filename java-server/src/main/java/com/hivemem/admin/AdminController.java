package com.hivemem.admin;

import com.hivemem.auth.AuthFilter;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.auth.TokenService;
import com.hivemem.consumption.DocumentDedupService;
import com.hivemem.summarize.SummarizerService;
import com.hivemem.sync.InstanceConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin")
public class AdminController {

    /**
     * Upper bound for one dedup-backfill page. The walk is synchronous, so a page is bounded by
     * whatever read timeout sits in front of the application, not by the database: at the measured
     * ~150 ms per cell this is already ~2.5 minutes. A larger ceiling would only offer the operator
     * a value that cannot finish through a reverse proxy, and a cut-off request gives no answer as
     * to whether the work committed.
     */
    private static final int MAX_BACKFILL_LIMIT = 1000;

    private final InstanceConfig instanceConfig;
    private final TokenService tokenService;
    private final com.hivemem.attachment.AttachmentChunkRepairService chunkRepair;
    private final ObjectProvider<SummarizerService> summarizer;
    private final DocumentDedupService dedup;

    public AdminController(InstanceConfig instanceConfig, TokenService tokenService,
                           com.hivemem.attachment.AttachmentChunkRepairService chunkRepair,
                           ObjectProvider<SummarizerService> summarizer,
                           DocumentDedupService dedup) {
        this.instanceConfig = instanceConfig;
        this.tokenService = tokenService;
        this.chunkRepair = chunkRepair;
        this.summarizer = summarizer;
        this.dedup = dedup;
    }

    @GetMapping("/identity")
    public ResponseEntity<?> identity(HttpServletRequest request) {
        if (!isAdmin(request)) return forbidden();
        return ResponseEntity.ok(Map.of("instance_uuid", instanceConfig.instanceId().toString()));
    }

    @PostMapping("/tokens")
    public ResponseEntity<?> createToken(@RequestBody CreateTokenRequest body, HttpServletRequest request) {
        if (!isAdmin(request)) return forbidden();
        AuthRole role;
        try {
            role = AuthRole.fromWireValue(body.role() == null ? "writer" : body.role());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid role"));
        }
        try {
            String token = tokenService.createToken(
                    body.name(), role, body.expiresInDays(), body.readRealms(), body.writeRealms());
            return ResponseEntity.ok(Map.of("name", body.name(), "role", role.wireValue(), "token", token));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/tokens")
    public ResponseEntity<?> listTokens(HttpServletRequest request) {
        if (!isAdmin(request)) return forbidden();
        return ResponseEntity.ok(Map.of("tokens", tokenService.listTokens(false, 200)));
    }

    @DeleteMapping("/tokens/{name}")
    public ResponseEntity<?> revokeToken(@PathVariable String name, HttpServletRequest request) {
        if (!isAdmin(request)) return forbidden();
        try {
            tokenService.revokeToken(name);
            return ResponseEntity.ok(Map.of("name", name, "revoked", true));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    /** One-time repair of attachments whose stored S3 object still has aws-chunked framing. */
    @PostMapping("/attachments/repair-chunked")
    public ResponseEntity<?> repairChunked(HttpServletRequest request) {
        if (!isAdmin(request)) return forbidden();
        var r = chunkRepair.repairAll();
        return ResponseEntity.ok(Map.of(
                "scanned", r.scanned(),
                "repaired_originals", r.repairedOriginals(),
                "repaired_thumbnails", r.repairedThumbnails(),
                "failed", r.failed()));
    }

    /** One-shot: give already-summarized documents (topic IS NULL) a short LLM title. */
    @PostMapping("/backfill-titles")
    public ResponseEntity<?> backfillTitles(@RequestParam(value = "limit", defaultValue = "200") int limit,
                                            HttpServletRequest request) {
        if (!isAdmin(request)) return forbidden();
        SummarizerService svc = summarizer.getIfAvailable();
        if (svc == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "summarizer disabled"));
        }
        int titled = svc.backfillTitles(limit);
        return ResponseEntity.ok(Map.of("titled", titled));
    }

    /** One-shot: backfill tax tags + valid_from for existing documents. */
    @PostMapping("/backfill-tax-date")
    public ResponseEntity<?> backfillTaxDate(@RequestParam(value = "limit", defaultValue = "200") int limit,
                                             HttpServletRequest request) {
        if (!isAdmin(request)) return forbidden();
        SummarizerService svc = summarizer.getIfAvailable();
        if (svc == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "summarizer disabled"));
        }
        int processed = svc.backfillTaxAndDate(limit);
        return ResponseEntity.ok(Map.of("processed", processed));
    }

    /**
     * One-off: deduplicate already-ingested scans (run after embeddings have been backfilled).
     *
     * <p>Resumable and paged, because the walk runs synchronously in this request at roughly 150 ms
     * per cell. Call without a cursor, then feed {@code after_created_at}/{@code after_id} from the
     * previous response back in until {@code remaining} is zero. A smaller {@code limit} buys
     * shorter responses, not less total work.
     *
     * <p><strong>Loop with the default limit rather than raising it.</strong> Because the walk is
     * synchronous, the page size directly sets the request duration, and a reverse proxy in front
     * of the application will cut a long request off — leaving the operator with an error and no
     * way to tell how much of the page committed (the walk itself is safe to resume, but only if
     * the cursor from the response was received). The default of 200 is ~30 s at today's cost and
     * matches the sibling backfill endpoints; {@link #MAX_BACKFILL_LIMIT} caps the opt-in.
     *
     * <p>Both halves of the cursor are required together. Half a cursor cannot be honoured — the
     * keyset compares the pair — so accepting one would silently restart the walk at the beginning
     * while {@code remaining} reports the full count and nothing marks the reset. That is precisely
     * the silent non-advance the keyset replaced LIMIT/OFFSET to avoid, so it is refused here
     * rather than interpreted. {@code limit} is clamped for the same reason: the documented
     * contract tells the operator to loop until {@code remaining} is zero, and a limit below 1
     * would never advance the cursor (or reach Postgres as a negative LIMIT).
     */
    @PostMapping("/dedup-backfill")
    public ResponseEntity<?> dedupBackfill(
            @RequestParam(value = "after_created_at", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime afterCreatedAt,
            @RequestParam(value = "after_id", required = false) UUID afterId,
            @RequestParam(value = "limit", defaultValue = "200") int limit,
            HttpServletRequest request) {
        if (!isAdmin(request)) return forbidden();
        if ((afterCreatedAt == null) != (afterId == null)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "after_created_at and after_id must be given together"));
        }
        DocumentDedupService.BackfillReport report =
                dedup.dedupBackfill(afterCreatedAt, afterId, Math.clamp(limit, 1, MAX_BACKFILL_LIMIT));
        // LinkedHashMap, not Map.of: the cursor is null on an exhausted or empty walk, and the
        // caller must see the key with a null value rather than a key that silently vanished.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("checked", report.checked());
        body.put("discarded", report.discarded());
        body.put("remaining", report.remaining());
        body.put("after_created_at",
                report.lastCreatedAt() == null ? null : report.lastCreatedAt().toString());
        body.put("after_id", report.lastId() == null ? null : report.lastId().toString());
        return ResponseEntity.ok(body);
    }

    /**
     * One-off retro pass: settle facts left behind on discarded cells by the deduplicator's fixed
     * orphan class (see {@link DocumentDedupService#factOrphanBackfill} for the class it targets
     * and why each cell is handled best-effort). Same shape as {@link #dedupBackfill}: resumable
     * keyset paging, both halves of the cursor required together, {@code limit} clamped into
     * {@code [1, MAX_BACKFILL_LIMIT]}, and a {@code LinkedHashMap} body so the cursor survives with
     * a null value on an exhausted walk instead of vanishing.
     *
     * <p><strong>The finish condition is {@code remaining == 0 AND skipped == 0 AND failed == 0},
     * not {@code remaining == 0} alone.</strong> {@code remaining} only counts orphans still ahead
     * of the cursor; a cell this page skipped or failed has already been stepped past and will
     * never reappear in a later {@code remaining} count. An operator who loops on {@code remaining}
     * alone will stop with unsettled orphans left behind them, believing the backfill is done.
     * Non-zero {@code skipped} or {@code failed} across a run means some cells need a human look,
     * not another call with the same cursor.
     */
    @PostMapping("/backfill-fact-orphans")
    public ResponseEntity<?> backfillFactOrphans(
            @RequestParam(value = "after_created_at", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime afterCreatedAt,
            @RequestParam(value = "after_id", required = false) UUID afterId,
            @RequestParam(value = "limit", defaultValue = "200") int limit,
            HttpServletRequest request) {
        if (!isAdmin(request)) return forbidden();
        if ((afterCreatedAt == null) != (afterId == null)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "after_created_at and after_id must be given together"));
        }
        DocumentDedupService.FactOrphanReport report =
                dedup.factOrphanBackfill(afterCreatedAt, afterId, Math.clamp(limit, 1, MAX_BACKFILL_LIMIT));
        // LinkedHashMap, not Map.of: the cursor is null on an exhausted or empty walk, and the
        // caller must see the key with a null value rather than a key that silently vanished.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("checked", report.checked());
        body.put("invalidated", report.invalidated());
        body.put("repointed", report.repointed());
        body.put("skipped", report.skipped());
        body.put("failed", report.failed());
        body.put("remaining", report.remaining());
        body.put("after_created_at",
                report.lastCreatedAt() == null ? null : report.lastCreatedAt().toString());
        body.put("after_id", report.lastId() == null ? null : report.lastId().toString());
        return ResponseEntity.ok(body);
    }

    private static boolean isAdmin(HttpServletRequest request) {
        AuthPrincipal principal = (AuthPrincipal) request.getAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE);
        return principal != null && principal.role() == AuthRole.ADMIN;
    }

    private static ResponseEntity<?> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "admin role required"));
    }

    public record CreateTokenRequest(String name, String role, Integer expiresInDays,
                                     List<String> readRealms, List<String> writeRealms) {

        /** Backward-compat: unscoped token creation (no realm ACL). */
        public CreateTokenRequest(String name, String role, Integer expiresInDays) {
            this(name, role, expiresInDays, null, null);
        }
    }
}
