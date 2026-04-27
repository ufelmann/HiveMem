package com.hivemem.sync;

import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class SyncPeerRepository {
    private final DSLContext dsl;

    public SyncPeerRepository(DSLContext dsl) { this.dsl = dsl; }

    public List<SyncPeer> findAllPeers() {
        return dsl.fetch("SELECT peer_uuid, peer_url, last_seen_seq, outbound_token FROM sync_peers")
                .map(r -> new SyncPeer(
                        r.get("peer_uuid", UUID.class),
                        r.get("peer_url", String.class),
                        r.get("last_seen_seq", Long.class),
                        r.get("outbound_token", String.class)));
    }

    public void updateLastSeenSeq(UUID peerUuid, long seq) {
        dsl.execute("UPDATE sync_peers SET last_seen_seq = ?, last_synced_at = now() WHERE peer_uuid = ?",
                seq, peerUuid);
    }

    public boolean isApplied(UUID opId) {
        return dsl.fetchOne("SELECT 1 FROM applied_ops WHERE op_id = ?", opId) != null;
    }

    public void recordApplied(UUID opId, UUID sourcePeer) {
        dsl.execute("INSERT INTO applied_ops (op_id, source_peer) VALUES (?, ?) ON CONFLICT DO NOTHING",
                opId, sourcePeer);
    }

    public void recordConflict(UUID cellId, UUID opA, UUID opB) {
        dsl.execute(
                "INSERT INTO sync_conflicts (id, cell_id, competing_op_a, competing_op_b) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), cellId, opA, opB);
    }

    public Map<String, Object> addPeer(UUID peerUuid, String peerUrl, String outboundToken) {
        dsl.execute("""
                INSERT INTO sync_peers (peer_uuid, peer_url, outbound_token)
                VALUES (?, ?, ?)
                ON CONFLICT (peer_uuid) DO UPDATE
                SET peer_url = EXCLUDED.peer_url,
                    outbound_token = EXCLUDED.outbound_token
                """, peerUuid, peerUrl, outboundToken);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("peer_uuid", peerUuid.toString());
        result.put("peer_url", peerUrl);
        return result;
    }

    public boolean removePeer(UUID peerUuid) {
        int rows = dsl.execute("DELETE FROM sync_peers WHERE peer_uuid = ?", peerUuid);
        return rows > 0;
    }

    public List<Map<String, Object>> listPeers() {
        return dsl.fetch("""
                SELECT peer_uuid, peer_url, last_seen_seq, last_synced_at
                FROM sync_peers
                ORDER BY peer_url
                """)
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("peer_uuid", r.get("peer_uuid", UUID.class).toString());
                    m.put("peer_url", r.get("peer_url", String.class));
                    m.put("last_seen_seq", r.get("last_seen_seq", Long.class));
                    OffsetDateTime ts = r.get("last_synced_at", OffsetDateTime.class);
                    m.put("last_synced_at", ts != null ? ts.toString() : null);
                    return m;
                });
    }
}
