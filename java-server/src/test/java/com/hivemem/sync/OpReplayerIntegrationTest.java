package com.hivemem.sync;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(OpReplayerIntegrationTest.TestConfig.class)
class OpReplayerIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean @Primary
        EmbeddingClient testEmbeddingClient() { return new FixedEmbeddingClient(); }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
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

    @Autowired DSLContext dsl;
    @Autowired OpReplayer replayer;
    @Autowired SyncPeerRepository syncPeerRepository;

    ObjectMapper objectMapper = new ObjectMapper();
    UUID sourcePeer = UUID.randomUUID();

    @Test
    void addCellReplayInsertsWithOriginalId() {
        UUID cellId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("cell_id", cellId.toString());
        payload.put("content", "hello from peer");
        payload.put("realm", "engineering");
        payload.put("signal", "facts");
        payload.put("topic", "test");
        payload.put("status", "committed");

        OpDto op = new OpDto(1L, UUID.randomUUID(), "add_cell", payload, OffsetDateTime.now());
        OpReplayer.ReplayResult result = replayer.replay(sourcePeer, op);

        assertThat(result).isEqualTo(OpReplayer.ReplayResult.REPLAYED);
        long count = dsl.fetchOne("SELECT count(*) AS c FROM cells WHERE id = ?", cellId)
                .get("c", Long.class);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void replayIsIdempotentForSameOpId() {
        UUID cellId = UUID.randomUUID();
        UUID opId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("cell_id", cellId.toString());
        payload.put("content", "hello");
        payload.put("realm", "eng");
        payload.put("signal", "facts");
        payload.put("topic", "t");
        payload.put("status", "committed");

        OpDto op = new OpDto(2L, opId, "add_cell", payload, OffsetDateTime.now());
        replayer.replay(sourcePeer, op);
        OpReplayer.ReplayResult second = replayer.replay(sourcePeer, op);
        assertThat(second).isEqualTo(OpReplayer.ReplayResult.SKIPPED);
    }

    @Test
    void conflictingAddCellIsRecorded() {
        UUID cellId = UUID.randomUUID();
        UUID sourcePeer1 = UUID.randomUUID();
        UUID sourcePeer2 = UUID.randomUUID();
        UUID opId1 = UUID.randomUUID();
        UUID opId2 = UUID.randomUUID();

        // First replay: inserts cell (simulates what peer1 sent)
        ObjectNode payload1 = objectMapper.createObjectNode();
        payload1.put("cell_id", cellId.toString());
        payload1.put("content", "from peer1");
        payload1.put("realm", "eng");
        payload1.put("signal", "facts");
        payload1.put("topic", "t");
        payload1.put("status", "committed");
        replayer.replay(sourcePeer1, new OpDto(1L, opId1, "add_cell", payload1, OffsetDateTime.now()));

        // Second replay: same cell UUID from different peer → conflict
        ObjectNode payload2 = objectMapper.createObjectNode();
        payload2.put("cell_id", cellId.toString());
        payload2.put("content", "from peer2");
        payload2.put("realm", "eng");
        payload2.put("signal", "facts");
        payload2.put("topic", "t");
        payload2.put("status", "committed");
        OpDto op2 = new OpDto(2L, opId2, "add_cell", payload2, OffsetDateTime.now());
        OpReplayer.ReplayResult result = replayer.replay(sourcePeer2, op2);

        assertThat(result).isEqualTo(OpReplayer.ReplayResult.CONFLICT);
        long conflicts = dsl.fetchOne("SELECT count(*) AS c FROM sync_conflicts WHERE cell_id = ?", cellId)
                .get("c", Long.class);
        assertThat(conflicts).isEqualTo(1L);
    }

    @Test
    void unknownOpTypeIsSkipped() {
        ObjectNode payload = objectMapper.createObjectNode();
        OpDto op = new OpDto(4L, UUID.randomUUID(), "unknown_future_op", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.UNKNOWN_OP);
    }

    @Test
    void reclassifyCellUpdatesClassification() {
        UUID cellId = insertMinimalCell();

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("cell_id", cellId.toString());
        payload.put("new_realm", "new-realm");
        payload.put("new_topic", "new-topic");
        payload.put("new_signal", "events");

        OpDto op = new OpDto(10L, UUID.randomUUID(), "reclassify_cell", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.REPLAYED);

        var row = dsl.fetchOne("SELECT realm, topic, signal FROM cells WHERE id = ?", cellId);
        assertThat(row.get("realm", String.class)).isEqualTo("new-realm");
        assertThat(row.get("topic", String.class)).isEqualTo("new-topic");
        assertThat(row.get("signal", String.class)).isEqualTo("events");
    }

    @Test
    void updateIdentityUpsertsKey() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("key", "test-identity-key");
        payload.put("content", "some identity content");
        payload.put("token_count", 4);

        OpDto op = new OpDto(11L, UUID.randomUUID(), "update_identity", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.REPLAYED);

        String content = dsl.fetchOne("SELECT content FROM identity WHERE key = ?", "test-identity-key")
                .get("content", String.class);
        assertThat(content).isEqualTo("some identity content");

        // second replay with different content updates in-place
        payload.put("content", "updated content");
        OpDto op2 = new OpDto(12L, UUID.randomUUID(), "update_identity", payload, OffsetDateTime.now());
        replayer.replay(sourcePeer, op2);
        content = dsl.fetchOne("SELECT content FROM identity WHERE key = ?", "test-identity-key")
                .get("content", String.class);
        assertThat(content).isEqualTo("updated content");
    }

    @Test
    void addReferenceInsertsWithOriginalId() {
        UUID refId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("reference_id", refId.toString());
        payload.put("title", "Test Reference");
        payload.put("url", "https://example.com");
        payload.put("ref_type", "article");
        payload.put("status", "read");

        OpDto op = new OpDto(13L, UUID.randomUUID(), "add_reference", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.REPLAYED);

        long count = dsl.fetchOne("SELECT count(*) AS c FROM references_ WHERE id = ?", refId)
                .get("c", Long.class);
        assertThat(count).isEqualTo(1L);

        // duplicate is skipped
        OpDto op2 = new OpDto(14L, UUID.randomUUID(), "add_reference", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op2)).isEqualTo(OpReplayer.ReplayResult.SKIPPED);
    }

    @Test
    void linkReferenceInsertsAndDeduplicates() {
        UUID cellId = insertMinimalCell();
        UUID refId = insertMinimalReference();

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("cell_id", cellId.toString());
        payload.put("reference_id", refId.toString());
        payload.put("relation", "source");

        OpDto op = new OpDto(15L, UUID.randomUUID(), "link_reference", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.REPLAYED);

        long count = dsl.fetchOne(
                "SELECT count(*) AS c FROM cell_references WHERE cell_id = ? AND reference_id = ?", cellId, refId)
                .get("c", Long.class);
        assertThat(count).isEqualTo(1L);

        // replay with a different opId should be skipped (same link already exists)
        OpDto op2 = new OpDto(16L, UUID.randomUUID(), "link_reference", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op2)).isEqualTo(OpReplayer.ReplayResult.SKIPPED);
    }

    @Test
    void diaryWriteInsertsEntry() {
        ensureAgent("diary-test-agent");
        UUID entryId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("entry_id", entryId.toString());
        payload.put("agent", "diary-test-agent");
        payload.put("entry", "Today I did something interesting.");

        OpDto op = new OpDto(17L, UUID.randomUUID(), "diary_write", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.REPLAYED);

        String entry = dsl.fetchOne("SELECT entry FROM agent_diary WHERE id = ?", entryId)
                .get("entry", String.class);
        assertThat(entry).isEqualTo("Today I did something interesting.");

        OpDto op2 = new OpDto(18L, UUID.randomUUID(), "diary_write", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op2)).isEqualTo(OpReplayer.ReplayResult.SKIPPED);
    }


    @Test
    void updateBlueprintInsertsNewVersion() {
        UUID blueprintId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("blueprint_id", blueprintId.toString());
        payload.put("realm", "sync-test-realm");
        payload.put("title", "Sync Test Blueprint");
        payload.put("narrative", "This is the narrative.");
        payload.put("agent_id", "test-agent");

        OpDto op = new OpDto(19L, UUID.randomUUID(), "update_blueprint", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.REPLAYED);

        long count = dsl.fetchOne("SELECT count(*) AS c FROM blueprints WHERE id = ?", blueprintId)
                .get("c", Long.class);
        assertThat(count).isEqualTo(1L);

        // duplicate blueprint_id skipped
        OpDto op2 = new OpDto(20L, UUID.randomUUID(), "update_blueprint", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op2)).isEqualTo(OpReplayer.ReplayResult.SKIPPED);
    }

    @Test
    void approvePendingTransitionsStatus() {
        UUID cellId = insertPendingCell();

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("decision", "committed");
        payload.putArray("ids").add(cellId.toString());

        OpDto op = new OpDto(21L, UUID.randomUUID(), "approve_pending", payload, OffsetDateTime.now());
        assertThat(replayer.replay(sourcePeer, op)).isEqualTo(OpReplayer.ReplayResult.REPLAYED);

        String status = dsl.fetchOne("SELECT status FROM cells WHERE id = ?", cellId)
                .get("status", String.class);
        assertThat(status).isEqualTo("committed");
    }

    // --- helpers ---

    private UUID insertMinimalCell() {
        UUID cellId = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO cells (id, content, embedding, realm, signal, topic, status)
                VALUES (?::uuid, 'test', array_fill(0, ARRAY[1024])::vector, 'test-realm', 'facts', 'test', 'committed')
                """, cellId);
        return cellId;
    }

    private UUID insertPendingCell() {
        UUID cellId = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO cells (id, content, embedding, realm, signal, topic, status)
                VALUES (?::uuid, 'pending-test', array_fill(0, ARRAY[1024])::vector, 'test-realm', 'facts', 'test', 'pending')
                """, cellId);
        return cellId;
    }

    private void ensureAgent(String name) {
        dsl.execute("""
                INSERT INTO agents (name, focus) VALUES (?, 'test focus')
                ON CONFLICT (name) DO NOTHING
                """, name);
    }

    private UUID insertMinimalReference() {
        UUID refId = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO references_ (id, title, status)
                VALUES (?::uuid, 'Test Ref', 'read')
                """, refId);
        return refId;
    }
}
