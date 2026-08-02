package com.hivemem.consumption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Re-stages one consumed file by content hash: locates the physical file (checking
 * {@code failed/}, then {@code processing/}, then {@code processed/}) and moves it back to the
 * watch root with {@link ConsumptionFileMover#moveToRoot}. Deliberately does NOT call
 * {@link ConsumptionFileRepository#stage} — {@code ConsumptionWatcher} does that itself once the
 * next poll re-hashes the file, which is the only place that correctly resets a row's state.
 *
 * <p>The original version of this class (removed) called {@code stage()} without moving anything.
 * That looked harmless but actively broke the retry path for a 'failed' row: staging it left the
 * physical file in {@code failed/} while the ledger row said 'staged', so
 * {@link ConsumptionFileRepository#findRetriableFailed} stopped selecting it (wrong state);
 * once the staged timestamp went stale, {@link ConsumptionFileRepository#findStaleProcessing}
 * picked the row up expecting a file in {@code processing/}, found nothing there, and
 * {@link ConsumptionFileRepository#markMissing} exhausted the retry budget
 * (attempts = GREATEST(attempts, retryLimit)) and overwrote {@code last_error} — permanently,
 * while the tool had already returned {@code restaged: true}.
 */
@Service
public class ConsumptionRetryService {

    private static final Logger log = LoggerFactory.getLogger(ConsumptionRetryService.class);

    private final ConsumptionFileRepository repo;
    private final Path root;
    private final ConsumptionFileMover mover;

    public ConsumptionRetryService(ConsumptionFileRepository repo, ConsumptionProperties props) {
        this.repo = repo;
        this.root = Path.of(props.getDir());
        this.mover = new ConsumptionFileMover(root);
    }

    public record Result(String sha256, boolean restaged, String error) {}

    public Result retry(String sha256) {
        var row = repo.findByHash(sha256);
        if (row.isEmpty()) {
            return new Result(sha256, false, "unknown sha256");
        }
        String filename = row.get().filename();
        Path failed = root.resolve(ConsumptionFileMover.FAILED).resolve(filename);
        Path processing = root.resolve(ConsumptionFileMover.PROCESSING).resolve(filename);
        // A batch flagged 'degraded' (findDegradedBatches has no state filter) completed analysis
        // normally, so its file already sits in processed/ — not failed/ or processing/. Retrying
        // it is exactly what the human-review button in consumption_queue is for: the boundaries
        // were guessed wrong and re-running the batch is the intended remedy. Duplicate cells from
        // the re-run are not a concern — DocumentDedupService discards re-scans of already-ingested
        // content.
        Path processed = root.resolve(ConsumptionFileMover.PROCESSED).resolve(filename);
        Path source = Files.isRegularFile(failed) ? failed
                : Files.isRegularFile(processing) ? processing
                : Files.isRegularFile(processed) ? processed
                : null;
        if (source == null) {
            log.warn("consumption_retry found no physical file for {} (sha256={}) in failed/, "
                    + "processing/ or processed/; ledger row left unchanged", filename, sha256);
            return new Result(sha256, false,
                    "no physical file for '" + filename + "' in failed/, processing/ or processed/");
        }
        try {
            mover.moveToRoot(source);
            log.info("consumption_retry re-staged {} (sha256={}) from {}",
                    filename, sha256, source.getParent().getFileName());
            return new Result(sha256, true, null);
        } catch (IOException e) {
            log.warn("consumption_retry could not move {} (sha256={}): {}", filename, sha256, e.toString());
            return new Result(sha256, false, "move failed: " + e.getMessage());
        }
    }
}
