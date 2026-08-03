package com.hivemem.consumption;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentDedupRepository {

    /** Form filter for lexemes usable as a lexical query term: lowercase letters only, long enough
     *  to carry meaning. Excludes numbers, dates and OCR fragments. */
    private static final String LEXEME_FORM = "^[a-zäöüß]{6,}$";
    /** How many of the target's lexemes drive the lexical query. Length is the cheap stand-in for
     *  rarity here; a per-lexeme document frequency would cost O(lexemes x cells) per document. */
    private static final int LEXEME_LIMIT = 32;

    private final DSLContext dsl;

    public DocumentDedupRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public record TargetCell(UUID id, String content, String source, OffsetDateTime createdAt) {}
    /**
     * A dedup candidate. {@code cosine} is null when the candidate was found by a channel that
     * does not compute a vector similarity (e.g. a future lexical channel), or when the vector
     * comparison itself could not be evaluated for this row — "not comparable", not "dissimilar".
     * {@code createdAt} drives the cross-channel merge order (oldest wins).
     */
    public record Candidate(UUID id, String content, Double cosine, OffsetDateTime createdAt) {}
    public record AttachmentKeys(UUID attachmentId, String s3KeyOriginal, String s3KeyThumbnail) {}

    /** The current (live) cell to evaluate, or empty if it is not current/committed. */
    public Optional<TargetCell> findTarget(UUID cellId) {
        Record r = dsl.fetchOne(
                "SELECT id, content, source, created_at FROM cells "
                + "WHERE id = ? AND valid_until IS NULL AND status = 'committed'", cellId);
        return r == null ? Optional.empty()
                : Optional.of(new TargetCell(
                        r.get("id", UUID.class),
                        r.get("content", String.class),
                        r.get("source", String.class),
                        r.get("created_at", OffsetDateTime.class)));
    }

    /**
     * Current committed scan cells that are strictly older than the target (created_at, id
     * tie-break) and are recalled by EITHER of two independent channels:
     * <ul>
     *   <li>vector — pgvector cosine within {@code recallThreshold} of the target's embedding;</li>
     *   <li>lexical — a {@code tsv} overlap on the target's own longest lexemes.</li>
     * </ul>
     * The second channel exists because the stored embedding of an oversized document is built from
     * its LLM summary, not its text (see {@code EmbeddingClient.encodeForCell}); two independently
     * worded summaries of the same document land well below {@code recallThreshold}, so the vector
     * channel alone never surfaces the pair. {@code cells.tsv} is generated from the FULL content
     * and is therefore unaffected by any embedding size limit.
     *
     * <p>{@code k} is a per-channel limit, so the union holds up to {@code 2k} rows. It is
     * deduplicated by id (a row found by both keeps its cosine) and returned ordered
     * {@code created_at ASC, id ASC} — the caller takes the FIRST row that passes its text gate, so
     * this order is what makes the oldest of the returned candidates win. Note this is "oldest of
     * the returned candidates", not "oldest of the duplicate group": each channel applies its
     * {@code LIMIT k} by similarity before the merge, so for a group larger than {@code 2k} the
     * true oldest can be missing from both rankings. That the oldest ultimately survives is a
     * property of the backfill (it walks old→new and discarded cells drop out of
     * {@code valid_until IS NULL}), not of this merge.
     */
    public List<Candidate> findSimilarOlderCandidates(UUID cellId, double recallThreshold, int k) {
        // Read once, hand to both channels: they need the same dimension literal, and the lookup is
        // a round trip. null means "target has no live embedding" — which short-circuits the vector
        // channel but must NOT stop the lexical one; those are exactly the cells it was built for.
        Integer dim = targetEmbeddingDim(cellId);
        Map<UUID, Candidate> merged = new LinkedHashMap<>();
        for (Candidate c : findVectorCandidates(cellId, dim, recallThreshold, k)) {
            merged.putIfAbsent(c.id(), c);
        }
        for (Candidate c : findLexicalCandidates(cellId, dim, k)) {
            merged.putIfAbsent(c.id(), c);
        }
        List<Candidate> out = new ArrayList<>(merged.values());
        out.sort(Comparator.comparing(Candidate::createdAt).thenComparing(Candidate::id));
        return out;
    }

    /**
     * Vector channel: pgvector cosine recall against the target's own embedding. Channel-local
     * short-circuit — if {@code dim} is null the target has no live embedding, so this channel has
     * nothing to compare against and returns an empty list; it does NOT abort the overall candidate
     * search, and the lexical channel runs regardless.
     */
    private List<Candidate> findVectorCandidates(
            UUID cellId, Integer dim, double recallThreshold, int k) {
        // The HNSW index idx_cells_embedding is an expression index on (embedding::vector(dim)); a
        // bare `embedding <=> ...` on the untyped vector column bypasses it and forces a sequential
        // scan (see KgSearchRepository.semanticSearch for the same fix on facts). The cast's typmod
        // must be a literal that textually matches the index expression, so it can't be a bind
        // parameter — but it also must not be hardcoded (the live dimension is determined at
        // runtime, not a fixed literal anywhere in the codebase; hardcoding would silently stop
        // using the index, or error outright, the moment the embedding model/dimension changes).
        // Instead read the target cell's OWN embedding dimension via vector_dims() and interpolate
        // it as a literal: for a LIVE (already re-encoded, or never-changed) cell it is guaranteed
        // to match the live index dimension. Note this is narrower than "always guaranteed": a
        // reencode does NOT bulk-NULL every embedding up front — EmbeddingMigrationService
        // overwrites embeddings in 100-row batches (see EmbeddingStateRepository.fetchCellBatch),
        // so old- and new-dimension vectors transiently coexist while it runs, and the HNSW index
        // itself is dropped for that whole window (see dropEmbeddingIndex/createEmbeddingIndex).
        // A dedup sweep racing that window can read a target cell whose OWN embedding is still
        // old-dimension while other cells are already new-dimension (or vice versa); the dynamic
        // %1$d cast then mismatches those other cells' vectors and Postgres raises a dimension
        // error for that comparison. DocumentDedupService's best-effort try/catch around this call
        // swallows it — dedup is skipped for that cell this pass, not a crash — and the sweep is
        // self-healing: once the reencode finishes, every embedding shares one dimension again.
        if (dim == null) {
            return List.of(); // target has no embedding (or isn't live) — nothing to compare against
        }
        String sql = ("""
                WITH target AS (SELECT embedding, created_at FROM cells WHERE id = ? AND valid_until IS NULL)
                SELECT c.id, c.content, c.created_at, %2$s AS cosine
                FROM cells c, target t
                WHERE c.valid_until IS NULL
                  AND c.status = 'committed'
                  AND c.source LIKE 'consumption:%%'
                  AND c.embedding IS NOT NULL
                  AND c.id <> ?
                  AND (c.created_at < t.created_at
                       OR (c.created_at = t.created_at AND c.id < ?))
                  AND (1 - (c.embedding::vector(%1$d) <=> t.embedding::vector(%1$d))) >= ?
                ORDER BY c.embedding::vector(%1$d) <=> t.embedding::vector(%1$d)
                LIMIT ?
                """).formatted(dim, cosineExpression(dim));
        List<Candidate> out = new ArrayList<>();
        for (Record r : dsl.fetch(sql, cellId, cellId, cellId, recallThreshold, k)) {
            out.add(new Candidate(
                    r.get("id", UUID.class),
                    r.get("content", String.class),
                    r.get("cosine", Double.class),
                    r.get("created_at", OffsetDateTime.class)));
        }
        return out;
    }

    /** The target cell's OWN live embedding dimension, or null if it has none (or is not live). */
    private Integer targetEmbeddingDim(UUID cellId) {
        Record r = dsl.fetchOne(
                "SELECT vector_dims(embedding) AS dim FROM cells WHERE id = ? AND valid_until IS NULL",
                cellId);
        return r == null ? null : r.get("dim", Integer.class);
    }

    /**
     * The cosine SELECT expression shared by both channels. Guarded, because a candidate row can
     * legitimately reach the SELECT list without a comparable vector: the lexical channel does not
     * require an embedding at all, and a re-encode in flight can leave a row on the old dimension
     * (a bare cast would then make Postgres RAISE for the whole statement). Both cases must yield
     * SQL NULL — "not comparable", not "dissimilar" — which is why {@code Candidate.cosine} is a
     * boxed {@code Double}. {@code dim == null} means the TARGET has no embedding, so nothing is
     * comparable at all and the expression degenerates to a typed NULL.
     */
    private static String cosineExpression(Integer dim) {
        if (dim == null) return "NULL::double precision";
        return ("CASE WHEN c.embedding IS NOT NULL AND vector_dims(c.embedding) = %1$d\n"
                + "     THEN 1 - (c.embedding::vector(%1$d) <=> t.embedding::vector(%1$d))\n"
                + "END").formatted(dim);
    }

    /**
     * Lexical channel: recall over the generated {@code cells.tsv} column, which is built from the
     * FULL content and is therefore blind to the embedding size limit that defeats the vector
     * channel on long documents.
     *
     * <p>Deliberately NOT {@code plainto_tsquery('simple', content)}: that ANDs every lexeme, so a
     * candidate only matches as a lexical SUPERSET of the target. A re-scan differing by a single
     * OCR token then fails in exactly the direction that matters (older twin one token shorter than
     * the new cell). Instead the target's own longest lexemes are OR-ed together.
     *
     * <p>Executed as TWO statements on purpose. As one statement Postgres evaluates the lexeme
     * InitPlan twice — once for {@code @@}, once for {@code ts_rank_cd} — which measured ~8x the
     * cost of fetching the lexemes first and passing the joined string as a bind parameter. The
     * {@code WHERE c.tsv @@ q} predicate is likewise mandatory: without it every surviving row gets
     * ranked. An empty lexeme set skips the channel entirely rather than issuing
     * {@code to_tsquery('simple', '')}, which only produces an empty query plus a server NOTICE.
     *
     * <p>Filters are identical to the vector channel's, so the "only an older cell can be the
     * original" invariant holds for both.
     */
    private List<Candidate> findLexicalCandidates(UUID cellId, Integer dim, int k) {
        List<String> lexemes = new ArrayList<>();
        for (Record r : dsl.fetch(
                "SELECT l.lexeme FROM cells c, unnest(c.tsv) AS l "
                + "WHERE c.id = ? AND c.valid_until IS NULL AND l.lexeme ~ ? "
                + "ORDER BY length(l.lexeme) DESC, l.lexeme ASC LIMIT ?",
                cellId, LEXEME_FORM, LEXEME_LIMIT)) {
            lexemes.add(r.get("lexeme", String.class));
        }
        if (lexemes.isEmpty()) return List.of();
        String tsquery = String.join(" | ", lexemes);

        // The target CTE supplies created_at (always) and the embedding the cosine expression needs
        // (only when the target actually has one).
        String sql = ("""
                WITH target AS (SELECT embedding, created_at FROM cells WHERE id = ? AND valid_until IS NULL)
                SELECT c.id, c.content, c.created_at, %s AS cosine
                FROM cells c, target t, to_tsquery('simple', ?) q
                WHERE c.valid_until IS NULL
                  AND c.status = 'committed'
                  AND c.source LIKE 'consumption:%%'
                  AND c.id <> ?
                  AND (c.created_at < t.created_at
                       OR (c.created_at = t.created_at AND c.id < ?))
                  AND c.tsv @@ q
                ORDER BY ts_rank_cd(c.tsv, q) DESC, c.created_at ASC, c.id ASC
                LIMIT ?
                """).formatted(cosineExpression(dim));
        List<Candidate> out = new ArrayList<>();
        for (Record r : dsl.fetch(sql, cellId, tsquery, cellId, cellId, k)) {
            out.add(new Candidate(
                    r.get("id", UUID.class),
                    r.get("content", String.class),
                    r.get("cosine", Double.class),
                    r.get("created_at", OffsetDateTime.class)));
        }
        return out;
    }

    /** All live committed consumption-sourced cell ids, oldest first (id tie-break). */
    public List<UUID> findLiveConsumptionCellIdsOldestFirst() {
        List<UUID> ids = new ArrayList<>();
        for (Record r : dsl.fetch(
                "SELECT id FROM cells "
                + "WHERE source LIKE 'consumption:%' AND valid_until IS NULL AND status = 'committed' "
                + "ORDER BY created_at ASC, id ASC")) {
            ids.add(r.get("id", UUID.class));
        }
        return ids;
    }

    public int softDeleteCell(UUID cellId) {
        return dsl.execute(
                "UPDATE cells SET valid_until = now() WHERE id = ? AND valid_until IS NULL", cellId);
    }

    /**
     * Atomically write the {@code duplicate_of} audit tunnel AND soft-delete the duplicate cell in a
     * single transaction. This keeps the core dedup invariant: we never soft-delete a cell without
     * recording why it disappeared, and never leave a {@code duplicate_of} tunnel hanging off a cell
     * that is still live. Attachment/S3 cleanup is deliberately NOT part of this transaction (it is
     * external, ref-count-guarded, and an orphaned binary is harmless next to losing the audit link).
     */
    public void linkAndSoftDelete(UUID duplicateCellId, UUID originalCellId, String note, String createdBy) {
        dsl.transaction(cfg -> {
            DSLContext tx = DSL.using(cfg);
            tx.execute(
                    "INSERT INTO tunnels (from_cell, to_cell, relation, note, status, created_by) "
                    + "VALUES (?, ?, 'duplicate_of', ?, 'committed', ?)",
                    duplicateCellId, originalCellId, note, createdBy);
            tx.execute(
                    "UPDATE cells SET valid_until = now() WHERE id = ? AND valid_until IS NULL",
                    duplicateCellId);
        });
    }

    /** Number of OTHER current cells linked to the attachment (excludes {@code excludingCellId}). */
    public int countOtherLiveCellsForAttachment(UUID attachmentId, UUID excludingCellId) {
        Record r = dsl.fetchOne(
                "SELECT count(*) AS n FROM cell_attachments ca "
                + "JOIN cells c ON c.id = ca.cell_id "
                + "WHERE ca.attachment_id = ? AND c.valid_until IS NULL AND ca.cell_id <> ?",
                attachmentId, excludingCellId);
        return r == null ? 0 : r.get("n", Long.class).intValue();
    }

    /** Extraction-source attachment keys for a cell, regardless of the cell's live/deleted state.
     *  Intended to be called right after a soft-delete, to drive S3 cleanup of the discarded copy. */
    public Optional<AttachmentKeys> findAttachmentKeysForCell(UUID cellId) {
        Record r = dsl.fetchOne(
                "SELECT a.id, a.s3_key_original, a.s3_key_thumbnail FROM attachments a "
                + "JOIN cell_attachments ca ON ca.attachment_id = a.id "
                + "WHERE ca.cell_id = ? AND ca.extraction_source = true LIMIT 1", cellId);
        return r == null ? Optional.empty()
                : Optional.of(new AttachmentKeys(
                        r.get("id", UUID.class),
                        r.get("s3_key_original", String.class),
                        r.get("s3_key_thumbnail", String.class)));
    }
}
