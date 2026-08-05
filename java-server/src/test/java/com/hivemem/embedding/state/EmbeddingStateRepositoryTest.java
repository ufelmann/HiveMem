package com.hivemem.embedding.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.hivemem.embedding.EmbeddingStateRepository;
import java.util.UUID;
import org.jooq.DSLContext;
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

/** Integration test for {@link EmbeddingStateRepository} against a real Postgres. Pattern:
 *  com.hivemem.chunk.CellChunkRepositoryTest.
 *
 *  <p>Deliberately lives in {@code com.hivemem.embedding.state}, NOT {@code com.hivemem.embedding}:
 *  a {@code @SpringBootTest} with no {@code classes=} (like {@code EmbeddingMigrationIntegrationTest}
 *  in the parent package) walks up the package hierarchy for the nearest
 *  {@code @SpringBootConfiguration} and binds to the first one found. Putting {@link TestApplication}
 *  in {@code com.hivemem.embedding} previously made it exactly that nearest configuration, silently
 *  hijacking {@code EmbeddingMigrationIntegrationTest}'s context (which then imported only
 *  {@link EmbeddingStateRepository} and broke every {@code @Autowired RateLimiter} in that test with
 *  {@code NoSuchBeanDefinitionException}). Do not move this class back into
 *  {@code com.hivemem.embedding}. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
        classes = EmbeddingStateRepositoryTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class EmbeddingStateRepositoryTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({EmbeddingStateRepository.class})
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
    EmbeddingStateRepository repo;

    @Autowired
    DSLContext dsl;

    /** Reproduces the bug this test guards against: discardChunks() must clear
     *  cells.chunked_content_md5, not just delete cell_chunks rows -- otherwise a cell that was
     *  already "considered" by the chunk sweep (marker == content_md5) is never reselected once its
     *  chunk rows are gone, since a model change never touches cells.content. The test must assert
     *  the marker is cleared, not merely that discardChunks() ran (design §3.5). */
    @Test
    void discardChunksDeletesChunkRowsAndClearsTheConsideredMarker() {
        UUID cellId = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO cells (id, content, realm, signal, topic, tags, status, valid_from, created_at)
                VALUES (?, ?, 'hivemem', 'facts', 'embedding-state-repo-test', ?, 'committed', now(), now())
                """, cellId, "some content", new String[] {});
        String contentMd5 = dsl.fetchOne("SELECT content_md5 FROM cells WHERE id = ?", cellId)
                .get(0, String.class);
        // Mark the cell as already considered by the sweep, matching the current content -- the
        // state that must NOT survive discardChunks(), or the cell is lost to the sweep forever.
        dsl.execute("UPDATE cells SET chunked_content_md5 = ? WHERE id = ?", contentMd5, cellId);
        dsl.execute("""
                INSERT INTO cell_chunks (cell_id, ordinal, content, cell_content_hash)
                VALUES (?, 0, 'chunk text', ?)
                """, cellId, contentMd5);

        repo.discardChunks();

        Integer remainingChunks = dsl.fetchOne("SELECT count(*)::int FROM cell_chunks WHERE cell_id = ?", cellId)
                .get(0, Integer.class);
        assertThat(remainingChunks).as("chunk rows are deleted").isZero();

        String chunkedContentMd5AfterDiscard = dsl.fetchOne(
                "SELECT chunked_content_md5 FROM cells WHERE id = ?", cellId)
                .get(0, String.class);
        assertThat(chunkedContentMd5AfterDiscard)
                .as("considered marker is cleared so the sweep can reselect this cell")
                .isNull();
    }
}
