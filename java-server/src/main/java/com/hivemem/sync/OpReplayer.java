package com.hivemem.sync;

import com.hivemem.embedding.EmbeddingClient;
import tools.jackson.databind.JsonNode;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Component
public class OpReplayer {

    private static final Logger log = LoggerFactory.getLogger(OpReplayer.class);

    public enum ReplayResult { REPLAYED, SKIPPED, CONFLICT, UNKNOWN_OP }

    public record BatchResult(int replayed, int skipped) {}

    private final DSLContext dsl;
    private final EmbeddingClient embeddingClient;
    private final SyncPeerRepository syncPeerRepository;

    public OpReplayer(DSLContext dsl, EmbeddingClient embeddingClient, SyncPeerRepository syncPeerRepository) {
        this.dsl = dsl;
        this.embeddingClient = embeddingClient;
        this.syncPeerRepository = syncPeerRepository;
    }

    public ReplayResult replay(UUID sourcePeer, OpDto op) {
        if (syncPeerRepository.isApplied(op.opId())) return ReplayResult.SKIPPED;

        ReplayResult result;
        try {
            result = executeOp(op);
        } catch (Exception e) {
            log.warn("Op replay failed op_id={} op_type={}", op.opId(), op.opType(), e);
            return ReplayResult.SKIPPED;
        }

        if (result == ReplayResult.REPLAYED || result == ReplayResult.CONFLICT) {
            syncPeerRepository.recordApplied(op.opId(), sourcePeer);
        }
        return result;
    }

    public BatchResult replayAll(UUID sourcePeer, List<OpDto> ops) {
        int replayed = 0, skipped = 0;
        for (OpDto op : ops) {
            ReplayResult r = replay(sourcePeer, op);
            if (r == ReplayResult.REPLAYED || r == ReplayResult.CONFLICT) replayed++;
            else skipped++;
        }
        return new BatchResult(replayed, skipped);
    }

    private ReplayResult executeOp(OpDto op) {
        JsonNode p = op.payload();
        return switch (op.opType()) {
            case "add_cell" -> replayAddCell(p);
            case "revise_cell" -> replayReviseCell(p);
            case "kg_add" -> replayKgAdd(p);
            case "kg_invalidate" -> replayKgInvalidate(p);
            case "revise_fact" -> replayReviseFact(p);
            case "add_tunnel" -> replayAddTunnel(p);
            case "remove_tunnel" -> replayRemoveTunnel(p);
            case "register_agent" -> replayRegisterAgent(p);
            default -> {
                log.debug("Unknown op_type='{}' — skipping", op.opType());
                yield ReplayResult.UNKNOWN_OP;
            }
        };
    }

    private ReplayResult replayAddCell(JsonNode p) {
        UUID cellId = uuid(p, "cell_id");
        boolean exists = dsl.fetchOne("SELECT 1 FROM cells WHERE id = ?", cellId) != null;
        if (exists) {
            UUID localOpId = findLocalOpForCell(cellId);
            syncPeerRepository.recordConflict(cellId,
                    localOpId != null ? localOpId : cellId,
                    cellId);
            return ReplayResult.CONFLICT;
        }
        String content = text(p, "content");
        List<Float> embedding = embeddingClient.encodeDocument(content);
        Float[] embArr = embedding.toArray(Float[]::new);
        String[] tags = arrayField(p, "tags");
        String[] keyPoints = arrayField(p, "key_points");
        OffsetDateTime validFrom = p.hasNonNull("valid_from")
                ? OffsetDateTime.parse(p.get("valid_from").asText()) : null;

        dsl.execute("""
                INSERT INTO cells (id, content, embedding, realm, signal, topic, source, tags, importance,
                                   summary, key_points, insight, actionability, status, created_by, valid_from)
                VALUES (?::uuid, ?, ?::vector, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        COALESCE(?::timestamptz, now()))
                """,
                cellId, content, embArr,
                text(p, "realm"), text(p, "signal"), text(p, "topic"), text(p, "source"),
                tags, intField(p, "importance"),
                text(p, "summary"), keyPoints, text(p, "insight"), text(p, "actionability"),
                textOrDefault(p, "status", "committed"), text(p, "agent_id"),
                validFrom);
        return ReplayResult.REPLAYED;
    }

    private ReplayResult replayReviseCell(JsonNode p) {
        UUID oldId = uuid(p, "cell_id");
        UUID newId = uuid(p, "new_cell_id");
        if (newId == null) return ReplayResult.SKIPPED;
        if (dsl.fetchOne("SELECT 1 FROM cells WHERE id = ?", newId) != null) return ReplayResult.SKIPPED;
        var meta = dsl.fetchOne("""
                SELECT realm, signal, topic, source, tags, importance, key_points, insight, actionability
                FROM cells WHERE id = ? AND valid_until IS NULL
                """, oldId);
        if (meta == null) {
            log.warn("revise_cell replay: old cell {} not found or already closed", oldId);
            return ReplayResult.SKIPPED;
        }
        String newContent = text(p, "new_content");
        List<Float> embedding = embeddingClient.encodeDocument(newContent);
        Float[] embArr = embedding.toArray(Float[]::new);
        String status = textOrDefault(p, "status", "committed");

        dsl.transaction(ctx -> {
            var tx = ctx.dsl();
            tx.execute("UPDATE cells SET valid_until = now() WHERE id = ? AND valid_until IS NULL", oldId);
            tx.execute("""
                    INSERT INTO cells (id, parent_id, content, embedding, realm, signal, topic, source, tags,
                                       importance, summary, key_points, insight, actionability, status, created_by, valid_from)
                    VALUES (?::uuid, ?::uuid, ?, ?::vector, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                    """,
                    newId, oldId, newContent, embArr,
                    meta.get("realm", String.class), meta.get("signal", String.class),
                    meta.get("topic", String.class), meta.get("source", String.class),
                    meta.get("tags", String[].class), meta.get("importance", Integer.class),
                    text(p, "new_summary"), meta.get("key_points", String[].class),
                    meta.get("insight", String.class), meta.get("actionability", String.class),
                    status, text(p, "agent_id"));
        });
        return ReplayResult.REPLAYED;
    }

    private ReplayResult replayKgAdd(JsonNode p) {
        UUID factId = uuid(p, "fact_id");
        if (dsl.fetchOne("SELECT 1 FROM facts WHERE id = ?", factId) != null) return ReplayResult.SKIPPED;
        OffsetDateTime validFrom = p.hasNonNull("valid_from")
                ? OffsetDateTime.parse(p.get("valid_from").asText()) : null;
        UUID sourceId = p.hasNonNull("source_id") ? UUID.fromString(p.get("source_id").asText()) : null;
        dsl.execute("""
                INSERT INTO facts (id, subject, predicate, "object", confidence, source_id, status, created_by, valid_from)
                VALUES (?::uuid, ?, ?, ?, ?, ?::uuid, ?, ?, COALESCE(?::timestamptz, now()))
                """,
                factId, text(p, "subject"), text(p, "predicate"), text(p, "object"),
                p.hasNonNull("confidence") ? p.get("confidence").asDouble() : 1.0,
                sourceId,
                textOrDefault(p, "status", "committed"), text(p, "agent_id"), validFrom);
        return ReplayResult.REPLAYED;
    }

    private ReplayResult replayKgInvalidate(JsonNode p) {
        UUID factId = uuid(p, "fact_id");
        dsl.execute("UPDATE facts SET valid_until = now() WHERE id = ? AND valid_until IS NULL", factId);
        return ReplayResult.REPLAYED;
    }

    private ReplayResult replayReviseFact(JsonNode p) {
        UUID oldId = uuid(p, "fact_id");
        UUID newId = uuid(p, "new_fact_id");
        if (newId == null) return ReplayResult.SKIPPED;
        if (dsl.fetchOne("SELECT 1 FROM facts WHERE id = ?", newId) != null) return ReplayResult.SKIPPED;
        var old = dsl.fetchOne(
                "SELECT subject, predicate, confidence, source_id FROM facts WHERE id = ? AND valid_until IS NULL",
                oldId);
        if (old == null) return ReplayResult.SKIPPED;
        String status = textOrDefault(p, "status", "committed");
        dsl.transaction(ctx -> {
            var tx = ctx.dsl();
            tx.execute("UPDATE facts SET valid_until = now() WHERE id = ? AND valid_until IS NULL", oldId);
            tx.execute("""
                    INSERT INTO facts (id, subject, predicate, "object", confidence, source_id, status, created_by, valid_from)
                    VALUES (?::uuid, ?, ?, ?, ?, ?::uuid, ?, ?, now())
                    """,
                    newId, old.get("subject", String.class), old.get("predicate", String.class),
                    text(p, "new_object"), old.get("confidence", Double.class),
                    old.get("source_id", UUID.class), status, text(p, "agent_id"));
        });
        return ReplayResult.REPLAYED;
    }

    private ReplayResult replayAddTunnel(JsonNode p) {
        UUID tunnelId = uuid(p, "tunnel_id");
        if (dsl.fetchOne("SELECT 1 FROM tunnels WHERE id = ?", tunnelId) != null) return ReplayResult.SKIPPED;
        dsl.execute("""
                INSERT INTO tunnels (id, from_cell, to_cell, relation, note, status, created_by)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?)
                """,
                tunnelId, uuid(p, "from_cell_id"), uuid(p, "to_cell_id"),
                text(p, "relation"), text(p, "note"),
                textOrDefault(p, "status", "committed"), text(p, "agent_id"));
        return ReplayResult.REPLAYED;
    }

    private ReplayResult replayRemoveTunnel(JsonNode p) {
        UUID tunnelId = uuid(p, "tunnel_id");
        dsl.execute("UPDATE tunnels SET valid_until = now() WHERE id = ? AND valid_until IS NULL", tunnelId);
        return ReplayResult.REPLAYED;
    }

    private ReplayResult replayRegisterAgent(JsonNode p) {
        String name = text(p, "name");
        dsl.execute("""
                INSERT INTO agents (name, focus, autonomy, schedule, model_routing, tools)
                VALUES (?, ?, COALESCE(?::jsonb, '{"default":"suggest_only"}'::jsonb), ?, ?::jsonb, ?)
                ON CONFLICT (name) DO UPDATE
                SET focus = EXCLUDED.focus, autonomy = EXCLUDED.autonomy,
                    schedule = EXCLUDED.schedule, model_routing = EXCLUDED.model_routing,
                    tools = EXCLUDED.tools
                """,
                name, text(p, "focus"),
                p.hasNonNull("autonomy") ? p.get("autonomy").toString() : null,
                text(p, "schedule"),
                p.hasNonNull("model_routing") ? p.get("model_routing").toString() : null,
                (Object) (p.hasNonNull("tools") ? arrayFromNode(p.get("tools")) : new String[0]));
        return ReplayResult.REPLAYED;
    }

    private UUID findLocalOpForCell(UUID cellId) {
        var row = dsl.fetchOne(
                "SELECT op_id FROM ops_log WHERE op_type = 'add_cell' AND payload->>'cell_id' = ? LIMIT 1",
                cellId.toString());
        return row == null ? null : row.get("op_id", UUID.class);
    }

    private static UUID uuid(JsonNode p, String field) {
        return p.hasNonNull(field) ? UUID.fromString(p.get(field).asText()) : null;
    }

    private static String text(JsonNode p, String field) {
        return p.hasNonNull(field) ? p.get(field).asText() : null;
    }

    private static String textOrDefault(JsonNode p, String field, String def) {
        return p.hasNonNull(field) ? p.get(field).asText() : def;
    }

    private static Integer intField(JsonNode p, String field) {
        return p.hasNonNull(field) ? p.get(field).asInt() : null;
    }

    private static String[] arrayField(JsonNode p, String field) {
        if (!p.hasNonNull(field)) return new String[0];
        JsonNode arr = p.get(field);
        if (!arr.isArray()) return new String[0];
        String[] result = new String[arr.size()];
        for (int i = 0; i < arr.size(); i++) result[i] = arr.get(i).asText();
        return result;
    }

    private static String[] arrayFromNode(JsonNode arr) {
        if (!arr.isArray()) return new String[0];
        String[] result = new String[arr.size()];
        for (int i = 0; i < arr.size(); i++) result[i] = arr.get(i).asText();
        return result;
    }
}
