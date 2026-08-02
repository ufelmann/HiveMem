package com.hivemem.consumption;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
public class ConsumptionFileRepository {

    private final DSLContext dsl;

    public ConsumptionFileRepository(DSLContext dsl) { this.dsl = dsl; }

    /**
     * Insert a new row for the given hash, or increment attempts if it already exists.
     * Always resets state to 'processing' so a retry sweep can re-stage failed rows.
     * The filename is refreshed on conflict: re-staged files may carry a collision suffix,
     * and the recovery sweep resolves the physical file by this name.
     */
    public void startProcessing(String sha256, String filename) {
        dsl.execute("""
                INSERT INTO consumption_file (sha256, filename, state, attempts)
                VALUES (?, ?, 'processing', 1)
                ON CONFLICT (sha256) DO UPDATE
                  SET attempts   = consumption_file.attempts + 1,
                      filename   = excluded.filename,
                      state      = 'processing',
                      updated_at = now()
                """, sha256, filename);
    }

    /**
     * Register a file BEFORE it is moved out of the watch root. State 'staged' means: known to the
     * ledger, not yet being worked on. Re-staging an existing row resets it — without the reset a
     * re-fed identical scan would keep its 'done' state (sha256 is UNIQUE) and be skipped forever.
     * Does NOT count as an attempt: {@code attempts} is left untouched on conflict, and inserted as
     * 0 for a new row. {@link #startProcessing} is the single place {@code attempts} increases —
     * staging twice before a single real processing pass must not burn two retries out of the
     * budget {@link #findRetriableFailed} enforces.
     */
    public void stage(String sha256, String filename) {
        dsl.execute("""
                INSERT INTO consumption_file (sha256, filename, state, attempts)
                VALUES (?, ?, 'staged', 0)
                ON CONFLICT (sha256) DO UPDATE
                  SET filename   = excluded.filename,
                      state      = 'staged',
                      updated_at = now()
                """, sha256, filename);
    }

    public void markDone(String sha256) {
        dsl.execute(
                "UPDATE consumption_file SET state='done', updated_at=now() WHERE sha256=?",
                sha256);
    }

    public void markFailed(String sha256, String lastError) {
        dsl.execute(
                "UPDATE consumption_file SET state='failed', last_error=?, updated_at=now() WHERE sha256=?",
                lastError, sha256);
    }

    /** Terminal: the row's file is gone, so retrying cannot help. Exhausts the retry budget so
     *  findRetriableFailed stops selecting it every sweep. */
    public void markMissing(String sha256, int retryLimit) {
        dsl.execute("""
                UPDATE consumption_file
                SET state = 'failed',
                    last_error = 'no physical file in processing/',
                    attempts = GREATEST(attempts, ?),
                    updated_at = now()
                WHERE sha256 = ?
                """, retryLimit, sha256);
    }

    public Optional<Row> findByHash(String sha256) {
        Record r = dsl.fetchOne(
                "SELECT sha256, filename, state, attempts, last_error FROM consumption_file WHERE sha256=?",
                sha256);
        return r == null ? Optional.empty() : Optional.of(map(r));
    }

    /** Returns rows stuck in 'processing' or 'staged' older than {@code olderThanSeconds}. */
    public List<Row> findStaleProcessing(int olderThanSeconds, int limit) {
        var rows = dsl.fetch("""
                SELECT sha256, filename, state, attempts, last_error
                FROM consumption_file
                WHERE state IN ('processing', 'staged')
                  AND updated_at < now() - make_interval(secs => ?)
                ORDER BY updated_at
                LIMIT ?
                """, olderThanSeconds, limit);
        List<Row> out = new ArrayList<>();
        for (Record r : rows) out.add(map(r));
        return out;
    }

    /** Bump updated_at on a processing row so the recovery sweep won't re-select it for another stale window. */
    public void touch(String sha256) {
        dsl.execute("UPDATE consumption_file SET updated_at = now() WHERE sha256 = ?", sha256);
    }

    /** Persist the actual on-disk filename after a collision-suffixed move, so the recovery
     *  sweep resolves the physical file under its real name instead of the stale original. */
    public void updateFilename(String sha256, String filename) {
        dsl.execute("UPDATE consumption_file SET filename = ?, updated_at = now() WHERE sha256 = ?",
                filename, sha256);
    }

    /** Record how many pages a batch had and how many of them lost their vision metadata. */
    public void recordPageStats(String sha256, int totalPages, int degradedPages) {
        dsl.execute("UPDATE consumption_file SET total_pages = ?, degraded_pages = ?, "
                + "updated_at = now() WHERE sha256 = ?", totalPages, degradedPages, sha256);
    }

    /** Returns rows in 'failed' state that have not yet exhausted their retry budget. */
    public List<Row> findRetriableFailed(int maxAttempts, int limit) {
        var rows = dsl.fetch("""
                SELECT sha256, filename, state, attempts, last_error
                FROM consumption_file
                WHERE state = 'failed'
                  AND attempts < ?
                ORDER BY updated_at
                LIMIT ?
                """, maxAttempts, limit);
        List<Row> out = new ArrayList<>();
        for (Record r : rows) out.add(map(r));
        return out;
    }

    /** All rows in 'failed' state, NEWEST first, for the review queue.
     *
     *  <p>Deliberately separate from {@link #findRetriableFailed}, which orders oldest-first because
     *  the retry sweep must drain its backlog in arrival order. The queue needs the opposite: with
     *  more failures than {@code limit}, oldest-first would show an operator the 50 oldest rows
     *  forever and never the ones that just broke. Unlike the sweep's query this one does not filter
     *  by retry budget — an exhausted row is exactly what a human is meant to look at. */
    public List<Row> findFailedNewestFirst(int limit) {
        var rows = dsl.fetch("""
                SELECT sha256, filename, state, attempts, last_error
                FROM consumption_file
                WHERE state = 'failed'
                ORDER BY updated_at DESC
                LIMIT ?
                """, limit);
        List<Row> out = new ArrayList<>();
        for (Record r : rows) out.add(map(r));
        return out;
    }

    /** Rows still in 'staged' or 'processing' whose {@code updated_at} is older than
     *  {@code olderThanSeconds} — a batch that stalled rather than failed. Without this the only
     *  trace of such a row in the review queue is an anonymous integer in {@link #countsByState},
     *  with no filename to act on, even though {@code ConsumptionRetryService} can re-stage it. */
    public List<StalledRow> findStalledRows(int olderThanSeconds, int limit) {
        var rows = dsl.fetch("""
                SELECT sha256, filename, state, updated_at,
                       EXTRACT(EPOCH FROM (now() - updated_at))::bigint AS age_seconds
                FROM consumption_file
                WHERE state IN ('staged', 'processing')
                  AND updated_at < now() - make_interval(secs => ?)
                ORDER BY updated_at
                LIMIT ?
                """, olderThanSeconds, limit);
        List<StalledRow> out = new ArrayList<>();
        for (Record r : rows) {
            out.add(new StalledRow(
                    r.get("sha256", String.class),
                    r.get("filename", String.class),
                    r.get("state", String.class),
                    r.get("updated_at", OffsetDateTime.class).toString(),
                    ((Number) r.get("age_seconds")).longValue()));
        }
        return out;
    }

    /** Which of the given on-disk filenames have at least one ledger row. Used by the
     *  reconciliation sweep to tell a genuine orphan (no row at all) from a file that belongs to
     *  the ledger. Deliberately returns presence only, not a {@code Map<String, Row>}: filename is
     *  NOT a unique key (the mover appends -1/-2 suffixes on collision, so two different sha256
     *  rows can legitimately share a filename), so picking "the" row for a name would silently
     *  discard one of them. */
    public Set<String> knownFilenames(Collection<String> filenames) {
        if (filenames.isEmpty()) return Set.of();
        var rows = dsl.fetch(
                "SELECT DISTINCT filename FROM consumption_file WHERE filename = ANY(?)",
                (Object) filenames.toArray(new String[0]));
        Set<String> out = new HashSet<>();
        for (Record r : rows) out.add(r.get("filename", String.class));
        return out;
    }

    /** Batches whose degraded page count is at least {@code minDegraded} AND above 2 % of their
     *  pages. Both conditions must hold: the percentage keeps large batches from being flagged for
     *  a couple of pages, the floor keeps small batches from being flagged for a single one.
     *  {@code total_pages > 0} also excludes rows where total_pages/degraded_pages are NULL (the
     *  {@code degradeToPending} path leaves them unset when a batch dies before analysis) — a NULL
     *  batch is unknown, not clean, and must not silently pass this filter either way. */
    public List<DegradedBatch> findDegradedBatches(int minDegraded, int limit) {
        var rows = dsl.fetch("""
                SELECT sha256, filename, total_pages, degraded_pages, updated_at
                FROM consumption_file
                WHERE degraded_pages >= ?
                  AND total_pages > 0
                  AND degraded_pages::numeric / total_pages > 0.02
                ORDER BY updated_at DESC
                LIMIT ?
                """, minDegraded, limit);
        List<DegradedBatch> out = new ArrayList<>();
        for (Record r : rows) {
            out.add(new DegradedBatch(
                    r.get("sha256", String.class),
                    r.get("filename", String.class),
                    (Integer) r.get("total_pages"),
                    (Integer) r.get("degraded_pages"),
                    r.get("updated_at", OffsetDateTime.class).toString()));
        }
        return out;
    }

    /** Row counts per ledger state, for the file level of the review queue. */
    public Map<String, Integer> countsByState() {
        var rows = dsl.fetch("SELECT state, count(*) AS n FROM consumption_file GROUP BY state");
        Map<String, Integer> out = new HashMap<>();
        for (Record r : rows) out.put(r.get("state", String.class), ((Number) r.get("n")).intValue());
        return out;
    }

    private static Row map(Record r) {
        return new Row(
                r.get("sha256", String.class),
                r.get("filename", String.class),
                r.get("state", String.class),
                (Integer) r.get("attempts"),
                r.get("last_error", String.class));
    }

    public record Row(String sha256, String filename, String state, int attempts, String lastError) {}

    public record DegradedBatch(String sha256, String filename, int totalPages,
                                int degradedPages, String updatedAt) {}

    /** A row that is neither done nor failed but has stopped moving. */
    public record StalledRow(String sha256, String filename, String state, String updatedAt,
                             long ageSeconds) {}
}
