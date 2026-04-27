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
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(SyncPeerRepositoryIntegrationTest.TestConfig.class)
class SyncPeerRepositoryIntegrationTest {

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
    @Autowired SyncPeerRepository repo;

    @Test
    void appliedOpsRoundTrip() {
        UUID opId = UUID.randomUUID();
        UUID sourcePeer = UUID.randomUUID();
        assertThat(repo.isApplied(opId)).isFalse();
        repo.recordApplied(opId, sourcePeer);
        assertThat(repo.isApplied(opId)).isTrue();
    }

    @Test
    void recordAppliedIsIdempotent() {
        UUID opId = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        repo.recordApplied(opId, peer);
        repo.recordApplied(opId, peer); // must not throw
        assertThat(repo.isApplied(opId)).isTrue();
    }

    @Test
    void updateLastSeenSeqUpdatesPeerRow() {
        UUID peerUuid = UUID.randomUUID();
        dsl.execute("INSERT INTO sync_peers (peer_uuid, peer_url, last_seen_seq) VALUES (?, ?, 0)",
                peerUuid, "http://peer:8421");
        repo.updateLastSeenSeq(peerUuid, 42L);
        Long seq = dsl.fetchOne("SELECT last_seen_seq FROM sync_peers WHERE peer_uuid = ?", peerUuid)
                .get("last_seen_seq", Long.class);
        assertThat(seq).isEqualTo(42L);
    }

    @Test
    void findAllPeersReturnsPeersWithToken() {
        UUID peerUuid = UUID.randomUUID();
        dsl.execute("INSERT INTO sync_peers (peer_uuid, peer_url, last_seen_seq, outbound_token) VALUES (?, ?, 0, ?)",
                peerUuid, "http://peer:8421", "tok-abc");
        var peers = repo.findAllPeers();
        assertThat(peers).anyMatch(p -> p.peerUuid().equals(peerUuid) && "tok-abc".equals(p.outboundToken()));
    }

    @Test
    void recordConflictInsertsRow() {
        UUID cellId = UUID.randomUUID();
        UUID opA = UUID.randomUUID();
        UUID opB = UUID.randomUUID();
        repo.recordConflict(cellId, opA, opB);
        long count = dsl.fetchOne("SELECT count(*) AS c FROM sync_conflicts WHERE cell_id = ?", cellId)
                .get("c", Long.class);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void addPeerInsertsRow() {
        UUID peerUuid = UUID.randomUUID();
        var result = repo.addPeer(peerUuid, "https://new-peer.example.com", "token-123");
        assertThat(result).containsEntry("peer_uuid", peerUuid.toString());
        assertThat(result).containsEntry("peer_url", "https://new-peer.example.com");

        var peers = repo.findAllPeers();
        assertThat(peers).anyMatch(p -> p.peerUuid().equals(peerUuid) && "token-123".equals(p.outboundToken()));
    }

    @Test
    void addPeerUpsertUpdatesUrlAndToken() {
        UUID peerUuid = UUID.randomUUID();
        repo.addPeer(peerUuid, "https://old.example.com", "old-token");
        repo.addPeer(peerUuid, "https://new.example.com", "new-token");

        var peers = repo.findAllPeers();
        assertThat(peers).anyMatch(p ->
                p.peerUuid().equals(peerUuid)
                && "https://new.example.com".equals(p.peerUrl())
                && "new-token".equals(p.outboundToken()));
    }

    @Test
    void removePeerDeletesRow() {
        UUID peerUuid = UUID.randomUUID();
        repo.addPeer(peerUuid, "https://to-remove.example.com", "tok");
        assertThat(repo.removePeer(peerUuid)).isTrue();
        assertThat(repo.removePeer(peerUuid)).isFalse();
    }

    @Test
    void listPeersDoesNotExposeToken() {
        UUID peerUuid = UUID.randomUUID();
        repo.addPeer(peerUuid, "https://list-test.example.com", "secret-token");

        var list = repo.listPeers();
        var entry = list.stream().filter(m -> peerUuid.toString().equals(m.get("peer_uuid"))).findFirst();
        assertThat(entry).isPresent();
        assertThat(entry.get()).doesNotContainKey("outbound_token");
        assertThat(entry.get()).containsKey("last_seen_seq");
        assertThat(entry.get()).containsKey("last_synced_at");
    }
}
