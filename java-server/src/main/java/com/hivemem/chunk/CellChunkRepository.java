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
     * either never chunked or chunked against a stale {@code content_md5}. Design §3.4.
     */
    public List<Candidate> selectCandidates(int minCellChars, int batchSize) {
        var rows = dsl.fetch("""
                SELECT c.id, c.content, c.content_md5
                FROM cells c
                WHERE c.valid_until IS NULL
                  AND c.status = 'committed'
                  AND length(c.content) > ?
                  AND (c.chunk_throttled_until IS NULL OR c.chunk_throttled_until < now())
                  AND NOT EXISTS (SELECT 1 FROM cell_chunks ch
                                  WHERE ch.cell_id = c.id AND ch.cell_content_hash = c.content_md5)
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
     * ones. {@code cell_content_hash} is read from {@code cells.content_md5} by the INSERT's own
     * subquery, never passed in from Java — that is the only way the read side (the selection
     * query's {@code NOT EXISTS} comparison) and the write side cannot drift apart (design §3.4).
     */
    public void replaceChunks(UUID cellId, List<ChunkToStore> chunks) {
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
