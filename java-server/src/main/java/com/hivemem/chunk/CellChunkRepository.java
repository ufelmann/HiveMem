package com.hivemem.chunk;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Selection, transactional replacement, cleanup and throttling for {@code cell_chunks}. Follows
 * the style of {@link com.hivemem.consumption.ConsumptionFileRepository}: raw SQL in text blocks
 * over a jOOQ {@link DSLContext}, constructor injection.
 *
 * <p>See design §3.4 for the selection query, the cleanup step and the error-handling contract
 * this repository exists to support.
 */
@Repository
public class CellChunkRepository {

    private final DSLContext dsl;

    public CellChunkRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Cells due for (re-)chunking: committed, current, over the size floor, not throttled, and
     * either never considered or considered against a stale {@code content_md5}. Design §3.4.
     *
     * <p>{@code chunked_content_md5 IS DISTINCT FROM content_md5} (not a {@code NOT EXISTS} against
     * {@code cell_chunks}) is deliberate and load-bearing: rule 6 (design §3.3) means 183 of 407
     * cells write NO chunk row at all, so a {@code NOT EXISTS} predicate would stay permanently
     * true for them — they would re-enter every tick's batch forever, occupying its {@code LIMIT}
     * slots and starving cells that actually need chunking. {@code chunked_content_md5} instead
     * records which content the sweep has LOOKED AT, independent of whether that produced any
     * rows, and {@link #replaceChunks} sets it in the same transaction as the chunk replacement on
     * every path except the throttle/failure path (a throttled cell must come back).
     */
    public List<Candidate> selectCandidates(int minCellChars, int batchSize) {
        var rows = dsl.fetch("""
                SELECT c.id, c.content, c.content_md5
                FROM cells c
                WHERE c.valid_until IS NULL
                  AND c.status = 'committed'
                  AND length(c.content) > ?
                  AND (c.chunk_throttled_until IS NULL OR c.chunk_throttled_until < now())
                  AND c.chunked_content_md5 IS DISTINCT FROM c.content_md5
                ORDER BY c.created_at DESC
                LIMIT ?
                """, minCellChars, batchSize);
        List<Candidate> out = new ArrayList<>();
        for (Record r : rows) {
            out.add(new Candidate(
                    r.get("id", UUID.class),
                    r.get("content", String.class),
                    r.get("content_md5", String.class)));
        }
        return out;
    }

    /**
     * Drops chunks belonging to superseded cells ({@code valid_until IS NOT NULL}). {@code ON
     * DELETE CASCADE} never fires for this on its own: cells are only ever soft-deleted (design
     * §3.4), so without this explicit step chunks of superseded revisions would accumulate
     * forever, scanned by {@code chunk_ann} but returned by no filter. Run once per sweep pass,
     * before selection.
     *
     * @return number of chunk rows removed
     */
    public int cleanupSupersededChunks() {
        return dsl.execute("""
                DELETE FROM cell_chunks ch USING cells c
                WHERE c.id = ch.cell_id AND c.valid_until IS NOT NULL
                """);
    }

    /**
     * Replaces a cell's entire chunk set in one transaction: delete the old rows, insert the new
     * ones (if any — an empty {@code chunks} is the rule-6 case, design §3.3), then mark the cell
     * as considered. All steps happen in the same transaction so the "considered" marker can never
     * be set without the chunk rows it describes, or vice versa.
     *
     * <p>{@code expectedContentMd5} is the {@code content_md5} captured by {@link #selectCandidates}
     * at selection time, and the final UPDATE is guarded by it:
     * {@code chunked_content_md5 = ? WHERE id = ? AND content_md5 = ?}. If the cell's content
     * changed between selection and this call, the guard fails, the UPDATE affects zero rows, and
     * the cell is simply picked up again on the next tick — the marker can only ever record the
     * content that was actually chunked, true by construction rather than by luck (there is no
     * in-place UPDATE of {@code cells.content} in this codebase today, but the guard keeps this
     * correct if that ever changes).
     *
     * <p>{@code cell_content_hash} is read from {@code cells.content_md5} by the INSERT's own
     * subquery, never passed in from Java — that is the only way it and {@code chunked_content_md5}
     * cannot drift apart (design §3.4). It remains an integrity/debugging field on the row; it is
     * no longer {@link #selectCandidates}'s selection basis.
     *
     * <p>Must NOT be called on the throttle/failure path: a cell whose embedding threw or returned
     * {@code null} must come back on the next tick, so nothing here runs for it.
     */
    public void replaceChunks(UUID cellId, String expectedContentMd5, List<ChunkToStore> chunks) {
        dsl.transaction(cfg -> {
            DSLContext tx = DSL.using(cfg);
            tx.execute("DELETE FROM cell_chunks WHERE cell_id = ?", cellId);
            for (ChunkToStore c : chunks) {
                tx.execute("""
                        INSERT INTO cell_chunks
                            (cell_id, ordinal, page_from, page_to, content, embedding, cell_content_hash)
                        SELECT ?, ?, ?, ?, ?, ?::vector, cl.content_md5
                        FROM cells cl WHERE cl.id = ?
                        """,
                        cellId, c.ordinal(), c.pageFrom(), c.pageTo(), c.content(), c.embedding(), cellId);
            }
            tx.execute("UPDATE cells SET chunked_content_md5 = ? WHERE id = ? AND content_md5 = ?",
                    expectedContentMd5, cellId, expectedContentMd5);
        });
    }

    /** Marks a cell's chunking throttled for {@code backoffSeconds} from now, without writing any
     *  chunk rows (design §3.4: a vectorless chunk would be invisible in ranking yet look "done"
     *  forever). The interval is computed in SQL (as {@code cells.summarize_throttled_until} does
     *  elsewhere) rather than passed as a Java {@link OffsetDateTime}, which the plain-SQL jOOQ
     *  binding used throughout this class cannot map onto {@code timestamptz} directly. */
    public void throttle(UUID cellId, long backoffSeconds) {
        dsl.execute("UPDATE cells SET chunk_throttled_until = now() + make_interval(secs => ?) WHERE id = ?",
                backoffSeconds, cellId);
    }

    public record Candidate(UUID id, String content, String contentMd5) {}

    /** A chunk ready to be persisted, with its embedding already computed. */
    public record ChunkToStore(int ordinal, Integer pageFrom, Integer pageTo, String content, Float[] embedding) {}
}
