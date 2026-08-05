package com.hivemem.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Integration tests for {@link CellChunkRepository} against a real Postgres, covering the
 *  selection/cleanup/replace/throttle cases from design §5.2. Pattern: CellSelectorRepositoryTest. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
        classes = CellChunkRepositoryTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CellChunkRepositoryTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({CellChunkRepository.class})
    static class TestApplication {}

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem")
            .withUsername("hivemem")
            .withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null
                            ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig())
                            .withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired
    CellChunkRepository repo;

    @Autowired
    DSLContext dsl;

    private static final int MIN_CELL_CHARS = 2000;

    @BeforeEach
    void seed() {
        dsl.execute("DELETE FROM cells WHERE topic = 'chunk-repo-test'");
    }

    private UUID insertCell(String content, String status, boolean softDeleted, OffsetDateTime throttledUntil) {
        UUID id = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO cells (id, content, realm, signal, topic, tags, status, valid_from,
                                    created_at, valid_until, chunk_throttled_until)
                VALUES (?, ?, 'hivemem', 'facts', 'chunk-repo-test', ?, ?, now(), now(),
                        ?::timestamptz, ?::timestamptz)
                """,
                id, content, new String[] {}, status,
                softDeleted ? OffsetDateTime.now().toString() : null,
                throttledUntil == null ? null : throttledUntil.toString());
        return id;
    }

    private String longContent(String marker) {
        return marker.repeat(1) + "x".repeat(MIN_CELL_CHARS + 100);
    }

    private void insertChunkRow(UUID cellId, int ordinal, String hash) {
        dsl.execute("""
                INSERT INTO cell_chunks (cell_id, ordinal, content, cell_content_hash)
                VALUES (?, ?, ?, ?)
                """, cellId, ordinal, "chunk text " + ordinal, hash);
    }

    private String contentMd5(UUID cellId) {
        return dsl.fetchOne("SELECT content_md5 FROM cells WHERE id = ?", cellId).get(0, String.class);
    }

    /** Marks a cell as already considered by the sweep, independent of writing any chunk rows —
     *  mirrors what {@link CellChunkRepository#replaceChunks} does in production. */
    private void markConsidered(UUID cellId, String hash) {
        dsl.execute("UPDATE cells SET chunked_content_md5 = ? WHERE id = ?", hash, cellId);
    }

    @Test
    void selectsCommittedCellsOverTheSizeFloorWithoutMatchingChunks() {
        UUID id = insertCell(longContent("a"), "committed", false, null);

        List<CellChunkRepository.Candidate> candidates = repo.selectCandidates(MIN_CELL_CHARS, 50);

        assertThat(candidates).extracting(CellChunkRepository.Candidate::id).contains(id);
    }

    @Test
    void cellAlreadyConsideredWithMatchingHashAcrossThreeChunksIsNotReselected() {
        UUID id = insertCell(longContent("b"), "committed", false, null);
        String hash = contentMd5(id);
        // Three chunk rows, not one -- a fixture with a single chunk row would also pass under a
        // wrong "hash means this row's own content" reading (design §5.2). Selection itself is
        // driven by chunked_content_md5, not by these rows' presence -- see the test below for the
        // rule-6 case where NO row exists at all yet the cell must still not be reselected.
        insertChunkRow(id, 0, hash);
        insertChunkRow(id, 1, hash);
        insertChunkRow(id, 2, hash);
        markConsidered(id, hash);

        List<CellChunkRepository.Candidate> candidates = repo.selectCandidates(MIN_CELL_CHARS, 50);

        assertThat(candidates).extracting(CellChunkRepository.Candidate::id).doesNotContain(id);
    }

    @Test
    void changedContentIsReselectedAfterAlreadyConsidered() {
        UUID id = insertCell(longContent("c"), "committed", false, null);
        // Stale marker: does not match content_md5 for the current content (e.g. the cell was
        // edited after it was last chunked).
        insertChunkRow(id, 0, "stale-hash-does-not-match");
        markConsidered(id, "stale-hash-does-not-match");

        List<CellChunkRepository.Candidate> candidates = repo.selectCandidates(MIN_CELL_CHARS, 50);

        assertThat(candidates).extracting(CellChunkRepository.Candidate::id).contains(id);
    }

    /** The termination property the fix round 1 exists for: a rule-6 cell (chunker returns an
     *  empty list because its content fits in one all-covering chunk) writes NO chunk row, but
     *  MUST still be marked considered so it is not reselected on the very next pass -- otherwise
     *  it (and the 183/407 cells like it) would occupy the batch's LIMIT slots on every tick
     *  forever and could starve cells that actually need chunking. */
    @Test
    void ruleSixCellIsSelectedOnceThenNeverAgainAfterReplaceChunksWithNoRows() {
        UUID id = insertCell(longContent("j"), "committed", false, null);

        List<CellChunkRepository.Candidate> firstPass = repo.selectCandidates(MIN_CELL_CHARS, 50);
        assertThat(firstPass).extracting(CellChunkRepository.Candidate::id).contains(id);

        // Simulates CellChunkSweep.processCandidate's rule-6 branch: chunker returned an empty
        // list, so replaceChunks is called with no chunks to store.
        repo.replaceChunks(id, List.of());

        List<CellChunkRepository.Candidate> secondPass = repo.selectCandidates(MIN_CELL_CHARS, 50);
        assertThat(secondPass).extracting(CellChunkRepository.Candidate::id).doesNotContain(id);

        Integer rowCount = dsl.fetchOne("SELECT count(*)::int FROM cell_chunks WHERE cell_id = ?", id)
                .get(0, Integer.class);
        assertThat(rowCount).as("rule 6: no chunk row is ever written").isZero();
    }

    @Test
    void rejectedCellIsNotSelected() {
        UUID id = insertCell(longContent("d"), "rejected", false, null);

        List<CellChunkRepository.Candidate> candidates = repo.selectCandidates(MIN_CELL_CHARS, 50);

        assertThat(candidates).extracting(CellChunkRepository.Candidate::id).doesNotContain(id);
    }

    @Test
    void supersededCellLosesItsChunksOnCleanup() {
        UUID id = insertCell(longContent("e"), "committed", true, null);
        String hash = contentMd5(id);
        insertChunkRow(id, 0, hash);

        int removed = repo.cleanupSupersededChunks();

        assertThat(removed).isGreaterThanOrEqualTo(1);
        Integer remaining = dsl.fetchOne("SELECT count(*)::int FROM cell_chunks WHERE cell_id = ?", id)
                .get(0, Integer.class);
        assertThat(remaining).isZero();
    }

    @Test
    void throttledCellIsNotSelectedUntilExpiry() {
        UUID future = insertCell(longContent("f"), "committed", false, OffsetDateTime.now().plusMinutes(15));
        UUID past = insertCell(longContent("g"), "committed", false, OffsetDateTime.now().minusMinutes(1));

        List<CellChunkRepository.Candidate> candidates = repo.selectCandidates(MIN_CELL_CHARS, 50);

        assertThat(candidates).extracting(CellChunkRepository.Candidate::id)
                .doesNotContain(future)
                .contains(past);
    }

    @Test
    void throttleSetsChunkThrottledUntil() {
        UUID id = insertCell(longContent("h"), "committed", false, null);
        OffsetDateTime expected = OffsetDateTime.now().plusMinutes(15);

        repo.throttle(id, 15 * 60L);

        OffsetDateTime stored = dsl.fetchOne("SELECT chunk_throttled_until FROM cells WHERE id = ?", id)
                .get(0, OffsetDateTime.class);
        assertThat(stored).isCloseTo(expected, org.assertj.core.api.Assertions.within(5, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    void replaceChunksReplacesTheWholeSetInOneTransactionAndReadsHashFromCells() {
        UUID id = insertCell(longContent("i"), "committed", false, null);
        String expectedHash = contentMd5(id);
        insertChunkRow(id, 0, "old-hash");
        insertChunkRow(id, 1, "old-hash");

        Float[] vec = new Float[] {0.1f, 0.2f, 0.3f};
        repo.replaceChunks(id, List.of(
                new CellChunkRepository.ChunkToStore(0, 1, 1, "new chunk zero", vec),
                new CellChunkRepository.ChunkToStore(1, 2, 3, "new chunk one", vec)));

        var rows = dsl.fetch("SELECT ordinal, content, page_from, page_to, cell_content_hash "
                + "FROM cell_chunks WHERE cell_id = ? ORDER BY ordinal", id);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("content", String.class)).isEqualTo("new chunk zero");
        assertThat(rows.get(0).get("page_from", Integer.class)).isEqualTo(1);
        assertThat(rows.get(1).get("page_to", Integer.class)).isEqualTo(3);
        // cell_content_hash came from cells.content_md5 at insert time, not from Java.
        assertThat(rows.get(0).get("cell_content_hash", String.class)).isEqualTo(expectedHash);
        assertThat(rows.get(1).get("cell_content_hash", String.class)).isEqualTo(expectedHash);

        // chunked_content_md5 is set in the same transaction, so the cell is not reselected.
        String chunkedContentMd5 = dsl.fetchOne("SELECT chunked_content_md5 FROM cells WHERE id = ?", id)
                .get(0, String.class);
        assertThat(chunkedContentMd5).isEqualTo(expectedHash);
    }
}
