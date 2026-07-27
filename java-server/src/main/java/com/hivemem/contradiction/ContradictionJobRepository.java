package com.hivemem.contradiction;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@code contradiction_jobs} — one row per Vistierie run dispatched for either
 * stage of contradiction detection ({@code kind} is {@code 'pairs'} or {@code 'cardinality'}).
 *
 * <p>This mirrors {@link com.hivemem.consumption.SeparationJobRepository} in shape and style, with
 * one deliberate divergence: {@link #findStale} covers both {@code 'awaiting'} and {@code
 * 'processing'}, not just {@code 'awaiting'}. The twin's {@code findStale()} only looks at
 * {@code 'awaiting'}, which strands any job whose process died after {@link #claim} flipped
 * it to {@code 'processing'} — that row (and every item it reserved) would sit invisible to every
 * sweep forever. Covering both statuses lets the reconcile sweep recover crashed in-flight jobs too.
 *
 * <p>Because the reconcile sweep can now act on a job that is merely slow (not dead), the terminal
 * writes {@link #markDone} and {@link #markFailed} are conditional UPDATEs, not unconditional ones:
 * a webhook finishing late and the sweep timing it out race for the same row, and exactly one of
 * them must win. Both return whether they were the winner.
 */
@Repository
public class ContradictionJobRepository {

    private final DSLContext dsl;

    public ContradictionJobRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public UUID create(UUID correlationId, String kind, int itemCount) {
        Record r = dsl.fetchOne("""
                INSERT INTO contradiction_jobs (correlation_id, kind, item_count)
                VALUES (?, ?, ?)
                RETURNING id
                """, correlationId, kind, itemCount);
        return r.get("id", UUID.class);
    }

    /** Record the Vistierie run id returned by dispatch so the callback can correlate deterministically. */
    public void attachRunId(UUID jobId, String runId) {
        dsl.execute("UPDATE contradiction_jobs SET vistierie_run_id=?, updated_at=now() WHERE id=?",
                runId, jobId);
    }

    /**
     * Update the real item count once it is known. Used by {@link ContradictionSweep}'s Stage B,
     * which creates the job before the re-reserve + top-up reservation determines how many pairs
     * actually ended up in it.
     */
    public void updateItemCount(UUID jobId, int itemCount) {
        dsl.execute("UPDATE contradiction_jobs SET item_count=?, updated_at=now() WHERE id=?",
                itemCount, jobId);
    }

    /**
     * Atomically claim an awaiting job before doing any work with it, so the webhook callback and
     * the reconcile sweep cannot both act on the same job.
     *
     * @return true iff this caller flipped the job from 'awaiting' to 'processing'
     */
    public boolean claim(UUID jobId) {
        int updated = dsl.execute(
                "UPDATE contradiction_jobs SET status='processing', updated_at=now() "
                        + "WHERE id=? AND status='awaiting'", jobId);
        return updated == 1;
    }

    public Optional<Job> findByRunId(String runId) {
        if (runId == null) return Optional.empty();
        Record r = dsl.fetchOne("""
                SELECT id, correlation_id, vistierie_run_id, kind, item_count, status
                FROM contradiction_jobs WHERE vistierie_run_id = ?
                """, runId);
        return r == null ? Optional.empty() : Optional.of(map(r));
    }

    /**
     * Jobs dispatched at least {@code olderThan} ago that are still 'awaiting' or 'processing' —
     * i.e. never picked up, or picked up by a process that then crashed before calling back.
     */
    public List<Job> findStale(Duration olderThan, int limit) {
        var rows = dsl.fetch("""
                SELECT id, correlation_id, vistierie_run_id, kind, item_count, status
                FROM contradiction_jobs
                WHERE status IN ('awaiting', 'processing')
                  AND updated_at < now() - (? * interval '1 second')
                ORDER BY updated_at LIMIT ?
                """, olderThan.toSeconds(), limit);
        List<Job> out = new ArrayList<>();
        for (Record r : rows) out.add(map(r));
        return out;
    }

    /**
     * Terminal transition to 'done', conditional on the job still being 'processing' so a webhook
     * that finishes late cannot silently overwrite a sweep's {@link #markFailed}.
     *
     * @return true iff this caller made the terminal write
     */
    public boolean markDone(UUID jobId) {
        return dsl.execute(
                "UPDATE contradiction_jobs SET status='done', updated_at=now() "
                        + "WHERE id=? AND status='processing'", jobId) == 1;
    }

    /**
     * Terminal transition to 'failed', conditional on the job not already being terminal so the
     * reconcile sweep cannot overwrite a webhook that already called {@link #markDone}.
     *
     * @return true iff this caller made the terminal write
     */
    public boolean markFailed(UUID jobId) {
        return dsl.execute(
                "UPDATE contradiction_jobs SET status='failed', updated_at=now() "
                        + "WHERE id=? AND status IN ('awaiting', 'processing')", jobId) == 1;
    }

    /**
     * Hard delete, used by the compensation path when Vistierie declines to create the run
     * (403 quota / 409 paused / 404 unregistered): the run never actually happened, so the row
     * must not count toward the daily dispatch ceiling.
     */
    public void delete(UUID jobId) {
        dsl.execute("DELETE FROM contradiction_jobs WHERE id=?", jobId);
    }

    /**
     * Runs dispatched since the last UTC midnight. Pinned to UTC deliberately: nothing in
     * application.yml, the Dockerfile or docker-compose.yml sets a server timezone, so pinning
     * here is the only way to keep the day boundary deterministic (and thus testable) instead of
     * drifting with the host's local timezone and DST.
     */
    public int countToday() {
        Record r = dsl.fetchOne("""
                SELECT count(*) AS c FROM contradiction_jobs
                WHERE created_at >= date_trunc('day', now(), 'UTC')
                """);
        return r.get("c", Integer.class);
    }

    private static Job map(Record r) {
        return new Job(
                r.get("id", UUID.class),
                r.get("correlation_id", UUID.class),
                r.get("vistierie_run_id", String.class),
                r.get("kind", String.class),
                r.get("item_count", Integer.class),
                r.get("status", String.class));
    }

    public record Job(UUID id, UUID correlationId, String runId, String kind, int itemCount, String status) {}
}
