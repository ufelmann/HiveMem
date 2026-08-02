package com.hivemem.consumption;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** Rows for the given on-disk filenames, keyed by filename. Used by the reconciliation sweep to
     *  decide what a physical file in processing/ actually is. */
    public Map<String, Row> findByFilenames(Collection<String> filenames) {
        if (filenames.isEmpty()) return Map.of();
        var rows = dsl.fetch(
                "SELECT sha256, filename, state, attempts, last_error FROM consumption_file "
                        + "WHERE filename = ANY(?)",
                (Object) filenames.toArray(new String[0]));
        Map<String, Row> out = new HashMap<>();
        for (Record r : rows) out.put(r.get("filename", String.class), map(r));
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
}
