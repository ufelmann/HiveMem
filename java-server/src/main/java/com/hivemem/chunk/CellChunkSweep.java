package com.hivemem.chunk;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.EmbeddingMigrationService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background sweep that keeps {@code cell_chunks} and its embeddings in step with {@code cells}.
 * Pattern: {@link com.hivemem.consumption.ConsumptionRecoverySweep} (scheduled + startup-run
 * component). See design §3.4 for the selection query, cleanup step and error handling this class
 * implements.
 *
 * <p><b>Two gates, not one</b> (design §3.4, Befund M5):
 * <ol>
 *   <li>{@link EmbeddingMigrationService#isReencodingActive()} — while a reencode is running (or
 *       about to write vectors in a new dimension), the sweep must not write chunk vectors that
 *       could end up in the wrong dimension.</li>
 *   <li>{@code startupComplete} — Spring starts {@code @Scheduled} tasks on
 *       {@code ContextRefreshedEvent}, which fires BEFORE {@code SpringApplication} invokes
 *       {@code callRunners()}. {@code EmbeddingMigrationService} carries {@code @Order(1)} and
 *       this class deliberately carries none, so the {@code AnnotationAwareOrderComparator} Spring
 *       Boot sorts {@code ApplicationRunner} beans with runs {@code EmbeddingMigrationService.run()}
 *       to completion (including any reencode and the accompanying index/function creation)
 *       strictly before {@code CellChunkSweep.run()} sets this flag. A {@code fixedRate} sweep has
 *       no such ordering against the scheduler, and an {@code initialDelay} would only be a bet on
 *       how long startup takes, not a guarantee — see the design doc for the failure this closes:
 *       writing chunk vectors in a new dimension while {@code ranked_search} is still rendered for
 *       the old one, which makes every search throw.</li>
 * </ol>
 *
 * <p>Gated on {@code hivemem.chunk.enabled} ({@link ChunkProperties#isEnabled()}'s default is
 * {@code true}, hence {@code matchIfMissing = true} here so an unset property still enables the
 * sweep in production). The gate matters beyond the feature switch itself: {@code @Scheduled}
 * beans are instantiated eagerly even under {@code spring.main.lazy-initialization=true}
 * (Spring's scheduling infra has to resolve them at startup to register the tasks), so without
 * this condition {@code CellChunkSweep} — and transitively {@link CellChunkRepository}, which
 * needs a {@code DSLContext} — would be constructed even in the DB-less
 * {@code HiveMemApplicationTest} context, which excludes DataSource/Flyway/jOOQ entirely. That
 * test explicitly sets {@code hivemem.chunk.enabled=false} to opt out, the same way it relies on
 * {@code hivemem.consumption.enabled}'s false default to keep
 * {@link com.hivemem.consumption.ConsumptionRecoverySweep} and
 * {@link com.hivemem.embedding.EmbeddingBackfillService} out.
 */
@Component
@ConditionalOnProperty(name = "hivemem.chunk.enabled", havingValue = "true", matchIfMissing = true)
public class CellChunkSweep implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CellChunkSweep.class);

    private final ChunkProperties props;
    private final CellChunkRepository repo;
    private final CellChunker chunker;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingMigrationService embeddingMigrationService;

    private final AtomicBoolean startupComplete = new AtomicBoolean(false);

    public CellChunkSweep(ChunkProperties props, CellChunkRepository repo, EmbeddingClient embeddingClient,
            EmbeddingMigrationService embeddingMigrationService) {
        this.props = props;
        this.repo = repo;
        this.chunker = new CellChunker(props);
        this.embeddingClient = embeddingClient;
        this.embeddingMigrationService = embeddingMigrationService;
    }

    /** Runs strictly after {@link EmbeddingMigrationService#run}, see the class javadoc. */
    @Override
    public void run(ApplicationArguments args) {
        startupComplete.set(true);
    }

    @Scheduled(fixedRateString = "${hivemem.chunk.sweep-interval-ms:60000}")
    public void sweep() {
        if (!props.isEnabled()) {
            return;
        }
        if (!startupComplete.get()) {
            log.debug("Chunk sweep skipped: startup is not complete yet");
            return;
        }
        if (embeddingMigrationService.isReencodingActive()) {
            log.debug("Chunk sweep skipped: an embedding reencode is active");
            return;
        }

        int cleaned = repo.cleanupSupersededChunks();
        if (cleaned > 0) {
            log.info("Chunk sweep cleaned up {} chunk row(s) of superseded cells", cleaned);
        }

        List<CellChunkRepository.Candidate> candidates =
                repo.selectCandidates(props.getMinCellChars(), props.getBatchSize());
        for (CellChunkRepository.Candidate candidate : candidates) {
            try {
                processCandidate(candidate);
            } catch (Exception e) {
                // A failing cell must not abort the batch (design §3.4).
                log.warn("Chunk sweep failed for cell {}: {}", candidate.id(), e.toString());
                repo.throttle(candidate.id(), props.getBackoff().toSeconds());
            }
        }
    }

    private void processCandidate(CellChunkRepository.Candidate candidate) {
        List<Chunk> chunks = chunker.chunk(candidate.content());
        if (chunks.isEmpty()) {
            // Rule 6 (design §3.3): a single all-covering chunk is not stored. Nothing failed, so
            // no throttle -- but the cell MUST still be marked considered (chunked_content_md5),
            // or selectCandidates' IS DISTINCT FROM predicate would stay permanently true for it
            // and it would re-enter every tick's batch forever (found while implementing this
            // task: see the migration's comment on chunked_content_md5).
            repo.replaceChunks(candidate.id(), List.of());
            return;
        }

        List<CellChunkRepository.ChunkToStore> toStore = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            List<Float> embedding = embeddingClient.encodeDocument(chunk.content());
            if (embedding == null) {
                // design §3.4: a vectorless chunk would be invisible in ranking yet look "done"
                // forever, so throttle the whole cell and write NO chunk rows for it.
                log.warn("Chunk sweep: embedding returned null for cell {} chunk {}; throttling",
                        candidate.id(), chunk.ordinal());
                repo.throttle(candidate.id(), props.getBackoff().toSeconds());
                return;
            }
            toStore.add(new CellChunkRepository.ChunkToStore(
                    chunk.ordinal(), chunk.pageFrom(), chunk.pageTo(), chunk.content(),
                    embedding.toArray(Float[]::new)));
        }

        repo.replaceChunks(candidate.id(), toStore);
    }
}
