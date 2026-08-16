package com.hivemem.consumption;

import com.hivemem.attachment.AttachmentRepository;
import com.hivemem.attachment.SeaweedFsClient;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Content-based dedup for scanned documents. Runs after OCR has populated a cell's text + embedding.
 * Two stages: recall, then a normalized-text Jaccard gate (textThreshold). Recall unions two
 * independent channels — pgvector cosine (DedupProperties.recallThreshold) and a lexical tsv match —
 * because a document too long to embed carries the vector of its summary, which two independent
 * summaries of the same scan do not share closely enough for the vector channel alone to see it.
 * On a confirmed duplicate the freshly-OCR'd cell is discarded (soft-deleted),
 * its attachment binary removed if unreferenced, and a duplicate_of tunnel points to the original.
 * Best-effort: any failure logs and leaves the document untouched.
 */
@Service
public class DocumentDedupService {

    private static final Logger log = LoggerFactory.getLogger(DocumentDedupService.class);
    private static final String DEDUP_ACTOR = "system-dedup";

    private final DocumentDedupRepository repo;
    private final AttachmentRepository attachments;
    private final SeaweedFsClient seaweed;
    private final DedupProperties props;

    public DocumentDedupService(DocumentDedupRepository repo, AttachmentRepository attachments,
                                SeaweedFsClient seaweed, DedupProperties props) {
        this.repo = repo;
        this.attachments = attachments;
        this.seaweed = seaweed;
        this.props = props;
    }

    /** @return the original cell id if {@code cellId} was a duplicate and got discarded, else empty. */
    public Optional<UUID> findAndDiscardDuplicate(UUID cellId) {
        if (!props.isEnabled()) return Optional.empty();
        try {
            Optional<DocumentDedupRepository.TargetCell> targetOpt = repo.findTarget(cellId);
            if (targetOpt.isEmpty()) return Optional.empty();
            DocumentDedupRepository.TargetCell target = targetOpt.get();
            // Only scanned/consumed documents are ever discarded — never manual or agent cells.
            String source = target.source();
            if (source == null || !source.startsWith("consumption:")) return Optional.empty();
            String targetText = target.content();
            if (targetText == null || targetText.isBlank()) return Optional.empty();

            List<DocumentDedupRepository.Candidate> candidates =
                    repo.findSimilarOlderCandidates(cellId, props.getRecallThreshold(), props.getCandidateK());
            // Hoisted out of the loop: with two candidate channels this list holds up to 2k rows and
            // re-shingling a large document per candidate dominates the whole check.
            Set<String> targetShingles = TextSimilarity.shingles(TextSimilarity.normalize(targetText));
            for (DocumentDedupRepository.Candidate c : candidates) {
                if (TextSimilarity.similarity(targetShingles, c.content()) >= props.getTextThreshold()) {
                    discard(cellId, c.id());
                    return Optional.of(c.id());
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Dedup check failed for cell {} (keeping it): {}", cellId, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Result of one backfill page. {@code lastCreatedAt}/{@code lastId} are the keyset cursor to
     * hand to the next call — the last cell this page looked at, or the cursor it was given when the
     * page was empty. {@code remaining} counts the live cells still ahead of that cursor; the caller
     * repeats until it is zero.
     */
    public record BackfillReport(int checked, int discarded,
                                 OffsetDateTime lastCreatedAt, UUID lastId, int remaining) {}

    /**
     * One-off retro pass, resumable: walk live consumption cells oldest→newest from the given keyset
     * cursor and discard any that are re-scans of a strictly-older cell. Oldest of each duplicate
     * group is kept. Calling findAndDiscardDuplicate on an already-discarded cell is a safe no-op,
     * so re-running a page is harmless. Best-effort overall.
     *
     * <p>Paged rather than unbounded because the whole walk runs synchronously inside one HTTP
     * request at roughly 150 ms per cell. The cursor is a keyset, not an offset — see
     * {@link DocumentDedupRepository#findLiveConsumptionCellIdsOldestFirst} for why anything else
     * silently fails to advance.
     */
    public BackfillReport dedupBackfill(OffsetDateTime afterCreatedAt, UUID afterId, int limit) {
        if (!props.isEnabled()) {
            log.info("Dedup backfill skipped: dedup is disabled");
            return new BackfillReport(0, 0, afterCreatedAt, afterId, 0);
        }
        List<DocumentDedupRepository.LiveCell> page =
                repo.findLiveConsumptionCellIdsOldestFirst(afterCreatedAt, afterId, limit);
        int discarded = 0;
        OffsetDateTime lastCreatedAt = afterCreatedAt;
        UUID lastId = afterId;
        for (DocumentDedupRepository.LiveCell cell : page) {
            if (findAndDiscardDuplicate(cell.id()).isPresent()) discarded++;
            lastCreatedAt = cell.createdAt();
            lastId = cell.id();
        }
        int remaining = repo.countLiveConsumptionCellsAfter(lastCreatedAt, lastId);
        log.info("Dedup backfill: checked {} consumption cells, discarded {}, {} remaining",
                page.size(), discarded, remaining);
        return new BackfillReport(page.size(), discarded, lastCreatedAt, lastId, remaining);
    }

    /**
     * Result of one fact-orphan backfill page. {@code invalidated}/{@code repointed} count only
     * cells whose settlement actually touched a row ({@code rowsAffected > 0}) — a cell resolved
     * to {@code REPOINTED} with nothing actually moved would otherwise overstate the work done.
     * {@code skipped} counts cells where no live fact target could be resolved (or, rarely, whose
     * {@code duplicate_of} tunnel was invalidated concurrently) — those still have an unsettled
     * orphan fact and need a human to look at them; {@code remaining} does NOT include them, since
     * it only counts orphans still ahead of the cursor, so {@code remaining == 0} alone is not the
     * finish condition — the skipped count must be checked too. {@code failed} counts cells where
     * settling itself threw (e.g. a concurrent {@code revise_cell} lock conflict); the cursor still
     * advances past them, so a deterministically-failing row cannot park the walk forever, but
     * nothing was written for it and it should be retried later. Each skip and failure is also
     * logged at WARN as it happens.
     */
    public record FactOrphanReport(int checked, int invalidated, int repointed, int skipped, int failed,
                                    OffsetDateTime lastCreatedAt, UUID lastId, int remaining) {}

    /**
     * One-off retro pass, resumable: walk discarded cells that still carry live facts (the orphan
     * class {@link DocumentDedupRepository#linkAndSoftDelete} now prevents going forward, but left
     * behind before the fix) and settle each one through {@link
     * DocumentDedupRepository#settleDiscardedCellFacts}, the same rule the live discard path uses.
     * Mirrors {@link #dedupBackfill}'s shape — same keyset-cursor paging, same {@code
     * props.isEnabled()} kill switch (the repoint branch rewrites {@code facts.source_id} AND
     * {@code facts.subject} with no audit trail of its own, so disabling dedup must stop this too),
     * same idempotency argument for the settled branches: a cell whose facts are settled no longer
     * matches {@link DocumentDedupRepository#findDiscardedCellsWithLiveFacts}'s predicate, so it
     * drops out of the next page on its own. Skipped and failed cells are the exception — see
     * {@link FactOrphanReport}.
     *
     * <p>Each cell is handled inside its own try/catch, mirroring {@link #dedupBackfill}'s
     * per-cell best-effort (there it comes from {@link #findAndDiscardDuplicate}'s own catch).
     * Without it, one failure (e.g. {@code isLiveInDedupScope}'s {@code FOR SHARE} colliding with a
     * concurrent {@code revise_cell}'s {@code FOR UPDATE} on the same cell) would throw out of the
     * whole page: everything already committed in this page would have no report and thus no
     * cursor, and — because the failing row is still first in cursor order — every retry would
     * re-read it and throw again, parking the walk at that cursor forever.
     */
    public FactOrphanReport factOrphanBackfill(OffsetDateTime afterCreatedAt, UUID afterId, int limit) {
        if (!props.isEnabled()) {
            log.info("Dedup fact backfill skipped: dedup is disabled");
            return new FactOrphanReport(0, 0, 0, 0, 0, afterCreatedAt, afterId, 0);
        }
        List<DocumentDedupRepository.LiveCell> page =
                repo.findDiscardedCellsWithLiveFacts(afterCreatedAt, afterId, limit);
        int invalidated = 0;
        int repointed = 0;
        int skipped = 0;
        int failed = 0;
        OffsetDateTime lastCreatedAt = afterCreatedAt;
        UUID lastId = afterId;
        for (DocumentDedupRepository.LiveCell cell : page) {
            try {
                Optional<UUID> original = repo.findDuplicateOfOriginal(cell.id());
                if (original.isEmpty()) {
                    // The page selection already requires a live duplicate_of tunnel; getting here
                    // means it was invalidated concurrently between the page read and this lookup.
                    // Never guess at a target — skip and let a human look at it.
                    log.warn("Dedup fact backfill: no live duplicate_of tunnel for discarded cell {}, skipping",
                            cell.id());
                    skipped++;
                } else {
                    DocumentDedupRepository.FactSettlement settlement =
                            repo.settleDiscardedCellFacts(cell.id(), original.get());
                    if (settlement.rowsAffected() > 0) {
                        switch (settlement.branch()) {
                            case INVALIDATED -> invalidated++;
                            case REPOINTED -> repointed++;
                            case SKIPPED -> { /* rowsAffected is always 0 for SKIPPED; unreachable here */ }
                        }
                    }
                    if (settlement.branch() == DocumentDedupRepository.FactSettlement.Branch.SKIPPED) {
                        skipped++;
                    }
                }
            } catch (Exception e) {
                // Best-effort per cell, like dedupBackfill: log, count, and keep the cursor moving
                // rather than let one failing row abort the whole page (and re-fail forever on retry).
                log.warn("Dedup fact backfill: settling discarded cell {} failed (leaving it for a "
                        + "later retry): {}", cell.id(), e.toString());
                failed++;
            }
            lastCreatedAt = cell.createdAt();
            lastId = cell.id();
        }
        int remaining = repo.countDiscardedCellsWithLiveFactsAfter(lastCreatedAt, lastId);
        log.info("Dedup fact backfill: checked {} discarded cells, invalidated {}, repointed {}, "
                + "skipped {}, failed {}, {} remaining",
                page.size(), invalidated, repointed, skipped, failed, remaining);
        return new FactOrphanReport(
                page.size(), invalidated, repointed, skipped, failed, lastCreatedAt, lastId, remaining);
    }

    private void discard(UUID duplicateCellId, UUID originalCellId) {
        // Snapshot attachment keys before soft-delete changes the live-reference count.
        Optional<DocumentDedupRepository.AttachmentKeys> keys =
                repo.findAttachmentKeysForCell(duplicateCellId);

        // Core invariant, ATOMIC: write the duplicate_of audit link AND soft-delete the cell in one
        // transaction, so we never soft-delete a cell without recording why, nor leave a tunnel
        // dangling from a still-live cell. If this throws, the outer best-effort catch keeps the doc.
        repo.linkAndSoftDelete(duplicateCellId, originalCellId,
                "auto-dedup: re-scanned content of " + originalCellId, DEDUP_ACTOR);

        // Best-effort cleanup of the now-orphaned binary, intentionally OUTSIDE the core transaction
        // (S3 is external; an orphaned binary is harmless next to losing the audit/soft-delete).
        keys.ifPresent(k -> {
            int others = repo.countOtherLiveCellsForAttachment(k.attachmentId(), duplicateCellId);
            if (others == 0) {
                try {
                    seaweed.delete(k.s3KeyOriginal());
                    if (k.s3KeyThumbnail() != null) seaweed.delete(k.s3KeyThumbnail());
                } catch (Exception e) {
                    log.warn("Dedup: S3 cleanup failed for attachment {}: {}", k.attachmentId(), e.toString());
                }
                attachments.softDelete(k.attachmentId());
            }
        });
        log.info("Dedup: discarded duplicate cell {} (re-scan of {})", duplicateCellId, originalCellId);
    }
}
