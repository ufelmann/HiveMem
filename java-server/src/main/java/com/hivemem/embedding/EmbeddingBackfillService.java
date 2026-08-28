package com.hivemem.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

// Gated on the consumption flag (already true in prod) because embedding_pending cells only arise
// from the consumption ingest path. This also keeps the @Scheduled bean out of the DB-less
// HiveMemApplicationTest context (which excludes DataSource/Flyway/jOOQ), mirroring the sibling
// sweeps (SummarizerService/OcrService/ConsumptionRecoverySweep) that are all @ConditionalOnProperty.
//
// Two gates, not one (mirrors com.hivemem.chunk.CellChunkSweep, see its javadoc for the full
// rationale): a fixedRate @Scheduled task has no ordering against ApplicationRunner beans, so its
// very first tick can fire at t≈0 -- before EmbeddingMigrationService's @Order(1) run() has even
// set isReencodingActive() to true, let alone finished. At that moment findCellsMissingEmbedding
// can legitimately return candidates (reencode's own clearEmbedding calls put rows there), so an
// unguarded first tick would run a full batch on the same cores as the starting reencode -- exactly
// the competition this class exists to prevent. An initialDelay would only be a bet on how long
// startup takes, not a guarantee, so this uses the same startupComplete flag as CellChunkSweep
// instead: EmbeddingBackfillService also implements ApplicationRunner (no @Order, so Spring Boot's
// AnnotationAwareOrderComparator runs it after EmbeddingMigrationService.run() completes), and
// backfill() refuses to do anything until that has happened.
@Service
@ConditionalOnProperty(name = "hivemem.consumption.enabled", havingValue = "true")
public class EmbeddingBackfillService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingBackfillService.class);

    private final EmbeddingBackfillRepository repo;
    private final EmbeddingClient client;
    private final EmbeddingMigrationService migrationService;
    private final int batchSize;

    private final AtomicBoolean startupComplete = new AtomicBoolean(false);

    public EmbeddingBackfillService(EmbeddingBackfillRepository repo, EmbeddingClient client,
            EmbeddingMigrationService migrationService,
            @Value("${hivemem.embedding.backfill-batch-size:50}") int batchSize) {
        this.repo = repo;
        this.client = client;
        this.migrationService = migrationService;
        this.batchSize = batchSize;
    }

    /** Runs strictly after {@link EmbeddingMigrationService#run}, see the class javadoc. */
    @Override
    public void run(ApplicationArguments args) {
        startupComplete.set(true);
    }

    @Scheduled(fixedRateString = "${hivemem.embedding.backfill-interval-ms:300000}")
    public void backfill() {
        if (!startupComplete.get()) {
            log.debug("Backfill sweep skipped: startup is not complete yet");
            return;
        }
        // A corpus reencode already saturates the embedding service's CPU budget; running the
        // sweep on top of it (every few minutes, for a pass that can take hours) would only add
        // competing embed calls that make each in-flight inference slower. See task-8-brief.md.
        if (migrationService.isReencodingActive()) {
            log.info("Skipping backfill sweep: embedding reencode is in progress.");
            return;
        }
        backfillCells();
        backfillFacts();
    }

    private void backfillCells() {
        List<UUID> ids = repo.findCellsMissingEmbedding(client.maxChars(), batchSize);
        for (UUID id : ids) {
            try {
                var snap = repo.findSnapshot(id).orElse(null);
                if (snap == null || snap.content() == null || snap.content().isBlank()) continue;
                List<Float> vec = client.encodeForCell(snap.content(), snap.summary());
                if (vec == null) continue;
                repo.setEmbedding(id, vec.toArray(Float[]::new));
            } catch (EmbeddingUnavailableException e) {
                log.warn("Embedding service still unavailable; deferring cell backfill (had {} pending)", ids.size());
                return;
            } catch (Exception e) {
                log.warn("Embedding backfill failed for cell {}: {}", id, e.getMessage());
            }
        }
    }

    private void backfillFacts() {
        List<UUID> ids = repo.findFactsMissingEmbedding(batchSize);
        for (UUID id : ids) {
            try {
                var snap = repo.findFactSnapshot(id).orElse(null);
                if (snap == null) continue;
                List<Float> vec = client.encodeDocument(snap.subject() + " " + snap.predicate() + " " + snap.object());
                if (vec == null) continue;
                repo.setFactEmbedding(id, vec.toArray(Float[]::new));
            } catch (EmbeddingUnavailableException e) {
                log.warn("Embedding service still unavailable; deferring fact backfill (had {} pending)", ids.size());
                return;
            } catch (Exception e) {
                log.warn("Embedding backfill failed for fact {}: {}", id, e.getMessage());
            }
        }
    }
}
