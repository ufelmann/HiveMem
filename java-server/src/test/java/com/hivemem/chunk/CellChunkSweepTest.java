package com.hivemem.chunk;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.EmbeddingMigrationService;
import com.hivemem.embedding.EmbeddingUnavailableException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CellChunkSweep}: gating, per-cell error handling and the throttle
 *  contract (design §5.2). Repository selection/cleanup/replace SQL itself is covered by
 *  {@link CellChunkRepositoryTest} against a real database; here the repository is mocked so the
 *  sweep's own control flow can be exercised in isolation, in the style of
 *  {@code ConsumptionRecoverySweepTest}. */
class CellChunkSweepTest {

    private static final int MIN_CELL_CHARS = 20;
    private static final int BATCH_SIZE = 50;

    private ChunkProperties props;
    private CellChunkRepository repo;
    private EmbeddingClient embeddingClient;
    private EmbeddingMigrationService embeddingMigrationService;
    private CellChunkSweep sweep;

    @BeforeEach
    void setUp() {
        props = new ChunkProperties();
        props.setTargetChars(50);
        props.setMaxChars(100);
        props.setMinCellChars(MIN_CELL_CHARS);
        props.setBatchSize(BATCH_SIZE);
        props.setBackoff(Duration.ofMinutes(15));
        props.setEnabled(true);

        repo = mock(CellChunkRepository.class);
        embeddingClient = mock(EmbeddingClient.class);
        embeddingMigrationService = mock(EmbeddingMigrationService.class);
        when(embeddingMigrationService.isReencodingActive()).thenReturn(false);
        when(repo.selectCandidates(anyInt(), anyInt())).thenReturn(List.of());

        sweep = new CellChunkSweep(props, repo, embeddingClient, embeddingMigrationService);
        sweep.run(null); // marks startup complete, mirroring the ordering guarantee it relies on
    }

    /** Content with two page markers, long enough that packing produces two chunks (well within
     *  maxChars each, so no further splitting) -- deliberately NOT the rule-6 single-chunk case.
     *  {@code marker} makes each candidate's chunks distinguishable to embeddingClient mocks. */
    private String twoChunkContent(String marker) {
        return "[page=1]" + marker + "-one-" + "a".repeat(50)
                + "[page=2]" + marker + "-two-" + "b".repeat(50);
    }

    @Test
    void sweepDoesNothingBeforeStartupIsComplete() {
        CellChunkSweep freshSweep = new CellChunkSweep(props, repo, embeddingClient, embeddingMigrationService);

        freshSweep.sweep();

        verify(repo, never()).cleanupSupersededChunks();
        verify(repo, never()).selectCandidates(anyInt(), anyInt());
    }

    @Test
    void sweepDoesNothingWhenDisabled() {
        props.setEnabled(false);

        sweep.sweep();

        verify(repo, never()).cleanupSupersededChunks();
        verify(repo, never()).selectCandidates(anyInt(), anyInt());
    }

    @Test
    void sweepDoesNotRunWhileReencodingIsActive() {
        when(embeddingMigrationService.isReencodingActive()).thenReturn(true);

        sweep.sweep();

        verify(repo, never()).cleanupSupersededChunks();
        verify(repo, never()).selectCandidates(anyInt(), anyInt());
    }

    @Test
    void sweepCleansUpBeforeSelectingUsingConfiguredThresholds() {
        sweep.sweep();

        verify(repo).cleanupSupersededChunks();
        verify(repo).selectCandidates(MIN_CELL_CHARS, BATCH_SIZE);
    }

    @Test
    void singleChunkCellIsMarkedConsideredWithNoRowsAndIsNotThrottled() {
        UUID id = UUID.randomUUID();
        when(repo.selectCandidates(anyInt(), anyInt()))
                .thenReturn(List.of(new CellChunkRepository.Candidate(id, "short unpaginated content", "hash")));

        sweep.sweep();

        // Rule 6 (design §3.3): no embedding call, no throttle -- but replaceChunks(id, "hash",
        // List.of()) MUST still run so chunked_content_md5 gets set and the cell is not reselected
        // forever (fix round 1: the IS DISTINCT FROM predicate's termination property). The hash
        // passed through is the one captured at selection time (fix round 2, item 4).
        verify(embeddingClient, never()).encodeDocument(any());
        verify(repo).replaceChunks(id, "hash", List.of());
        verify(repo, never()).throttle(eq(id), anyLong());
    }

    @Test
    void nullEmbeddingThrottlesTheCellAndWritesNoChunkRow() {
        UUID id = UUID.randomUUID();
        when(repo.selectCandidates(anyInt(), anyInt()))
                .thenReturn(List.of(new CellChunkRepository.Candidate(id, twoChunkContent("x"), "hash")));
        when(embeddingClient.encodeDocument(any())).thenReturn(null);

        sweep.sweep();

        verify(repo, never()).replaceChunks(eq(id), any(), any());
        verify(repo).throttle(id, props.getBackoff().toSeconds());
    }

    @Test
    void throwingEmbeddingThrottlesTheCellAndWritesNoChunkRow() {
        UUID id = UUID.randomUUID();
        when(repo.selectCandidates(anyInt(), anyInt()))
                .thenReturn(List.of(new CellChunkRepository.Candidate(id, twoChunkContent("y"), "hash")));
        when(embeddingClient.encodeDocument(any()))
                .thenThrow(new EmbeddingUnavailableException("boom", null));

        sweep.sweep();

        verify(repo, never()).replaceChunks(eq(id), any(), any());
        verify(repo).throttle(id, props.getBackoff().toSeconds());
    }

    @Test
    void aFailingCellDoesNotAbortTheRestOfTheBatch() {
        UUID failing = UUID.randomUUID();
        UUID succeeding = UUID.randomUUID();
        when(repo.selectCandidates(anyInt(), anyInt()))
                .thenReturn(List.of(
                        new CellChunkRepository.Candidate(failing, twoChunkContent("fail"), "hash-a"),
                        new CellChunkRepository.Candidate(succeeding, twoChunkContent("ok"), "hash-b")));

        when(embeddingClient.encodeDocument(argThat(s -> s != null && s.contains("fail"))))
                .thenThrow(new EmbeddingUnavailableException("boom", null));
        when(embeddingClient.encodeDocument(argThat(s -> s != null && s.contains("ok"))))
                .thenReturn(List.of(0.1f, 0.2f));

        sweep.sweep();

        verify(repo).throttle(failing, props.getBackoff().toSeconds());
        verify(repo, never()).replaceChunks(eq(failing), any(), any());
        verify(repo).replaceChunks(eq(succeeding), any(), any());
        verify(repo, never()).throttle(eq(succeeding), anyLong());
    }
}
