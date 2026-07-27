package com.hivemem.contradiction;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ContradictionJobRepositoryIT extends ContradictionITSupport {

    @Autowired DSLContext dsl;
    @Autowired ContradictionJobRepository jobs;

    @BeforeEach
    void cleanUp() {
        dsl.execute("DELETE FROM contradiction_jobs");
    }

    @Test
    void claimFlipsAwaitingToProcessingExactlyOnce() {
        UUID id = jobs.create(UUID.randomUUID(), "pairs", 5);
        assertThat(jobs.claim(id)).isTrue();
        assertThat(jobs.claim(id)).isFalse();
    }

    @Test
    void findStaleCoversAwaitingAndProcessing() {
        UUID awaiting = jobs.create(UUID.randomUUID(), "pairs", 1);
        UUID processing = jobs.create(UUID.randomUUID(), "cardinality", 1);
        jobs.claim(processing);
        age(awaiting);
        age(processing);

        List<ContradictionJobRepository.Job> stale = jobs.findStale(Duration.ofMinutes(10), 10);

        assertThat(stale).extracting(ContradictionJobRepository.Job::id)
                .containsExactlyInAnyOrder(awaiting, processing);
    }

    @Test
    void findStaleIgnoresTerminalAndFreshJobs() {
        UUID fresh = jobs.create(UUID.randomUUID(), "pairs", 1);

        UUID done = jobs.create(UUID.randomUUID(), "pairs", 1);
        assertThat(jobs.claim(done)).isTrue();
        assertThat(jobs.markDone(done)).isTrue();
        age(done);

        UUID failed = jobs.create(UUID.randomUUID(), "pairs", 1);
        assertThat(jobs.markFailed(failed)).isTrue();
        age(failed);

        assertThat(jobs.findStale(Duration.ofMinutes(10), 10))
                .extracting(ContradictionJobRepository.Job::id)
                .doesNotContain(fresh, done, failed);
    }

    /**
     * Pins the real UTC-midnight boundary instead of just moving a row two days back — a coarse
     * shift like that would still pass even if the boundary were off by many hours (e.g. by a
     * server-timezone offset such as Europe/Berlin). Inserting exactly one minute either side of
     * the boundary fails on any timezone-driven drift.
     */
    @Test
    void countTodayCountsFromUtcMidnightOnly() {
        dsl.execute("""
                INSERT INTO contradiction_jobs (correlation_id, kind, item_count, created_at)
                VALUES (?, 'pairs', 1, date_trunc('day', now(), 'UTC') - interval '1 minute')
                """, UUID.randomUUID());
        dsl.execute("""
                INSERT INTO contradiction_jobs (correlation_id, kind, item_count, created_at)
                VALUES (?, 'pairs', 1, date_trunc('day', now(), 'UTC') + interval '1 minute')
                """, UUID.randomUUID());

        assertThat(jobs.countToday()).isEqualTo(1);
    }

    @Test
    void findByRunIdMatchesTheCallbackJoinKey() {
        UUID id = jobs.create(UUID.randomUUID(), "cardinality", 2);
        jobs.attachRunId(id, "run-abc");
        assertThat(jobs.findByRunId("run-abc")).isPresent()
                .get().extracting(ContradictionJobRepository.Job::kind).isEqualTo("cardinality");
        assertThat(jobs.findByRunId("nope")).isEmpty();
        assertThat(jobs.findByRunId(null)).isEmpty();
    }

    @Test
    void deleteRemovesACompensatedJob() {
        UUID id = jobs.create(UUID.randomUUID(), "pairs", 1);
        jobs.delete(id);
        assertThat(jobs.countToday()).isZero();
    }

    @Test
    void reclaimStaleReclaimsAnAgedAwaitingJob() {
        UUID id = jobs.create(UUID.randomUUID(), "pairs", 1);
        age(id);

        assertThat(jobs.reclaimStale(id, Duration.ofMinutes(10))).isTrue();
        assertThat(jobs.claim(id)).isFalse(); // already processing now
    }

    @Test
    void reclaimStaleReclaimsAnAgedProcessingJob() {
        UUID id = jobs.create(UUID.randomUUID(), "pairs", 1);
        jobs.claim(id);
        age(id);

        assertThat(jobs.reclaimStale(id, Duration.ofMinutes(10))).isTrue();
    }

    @Test
    void reclaimStaleRefusesAFreshJobRegardlessOfStatus() {
        UUID awaiting = jobs.create(UUID.randomUUID(), "pairs", 1);
        UUID processing = jobs.create(UUID.randomUUID(), "pairs", 1);
        jobs.claim(processing);

        assertThat(jobs.reclaimStale(awaiting, Duration.ofMinutes(10))).isFalse();
        assertThat(jobs.reclaimStale(processing, Duration.ofMinutes(10))).isFalse();
    }

    @Test
    void reclaimStaleRefusesATerminalJob() {
        UUID id = jobs.create(UUID.randomUUID(), "pairs", 1);
        assertThat(jobs.markFailed(id)).isTrue();
        age(id);

        assertThat(jobs.reclaimStale(id, Duration.ofMinutes(10))).isFalse();
    }

    private void age(UUID jobId) {
        dsl.execute("UPDATE contradiction_jobs SET updated_at = now() - interval '1 hour' WHERE id = ?",
                jobId);
    }
}
