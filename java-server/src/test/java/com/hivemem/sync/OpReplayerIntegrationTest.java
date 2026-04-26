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
}
