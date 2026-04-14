package com.hivemem.write;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class WriteToolRepository {

    private static final DateTimeFormatter PYTHON_TIMESTAMP_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart()
            .appendFraction(java.time.temporal.ChronoField.NANO_OF_SECOND, 0, 6, true)
            .optionalEnd()
            .appendOffset("+HH:MM", "+00:00")
            .toFormatter();

    private final DSLContext dslContext;

    public WriteToolRepository(DSLContext dslContext) {
        this.dslContext = dslContext;
    }

    public Map<String, Object> addFact(
            String subject,
            String predicate,
            String object,
            double confidence,
            UUID sourceId,
            String status,
            String createdBy,
            OffsetDateTime validFrom
    ) {
        Record row = dslContext.fetchOne("""
                INSERT INTO facts (subject, predicate, "object", confidence, source_id, status, created_by, valid_from)
                VALUES (?, ?, ?, ?, ?, ?, ?, COALESCE(?::timestamptz, now()))
                RETURNING id, subject, predicate, "object", status
                """, subject, predicate, object, confidence, sourceId, status, createdBy, validFrom);
        return factRow(row);
    }

    public Map<String, Object> addDrawer(
            String content,
            List<Float> embedding,
            String wing,
            String hall,
            String room,
            String source,
            List<String> tags,
            Integer importance,
            String summary,
            List<String> keyPoints,
            String insight,
            String actionability,
            String status,
            String createdBy,
            OffsetDateTime validFrom
    ) {
        String[] tagArray = tags == null ? new String[0] : tags.toArray(String[]::new);
        String[] keyPointArray = keyPoints == null ? new String[0] : keyPoints.toArray(String[]::new);
        Float[] embeddingArray = embedding == null ? null : embedding.toArray(Float[]::new);
        Record row = dslContext.fetchOne("""
                INSERT INTO drawers (content, embedding, wing, hall, room, source, tags, importance,
                                     summary, key_points, insight, actionability, status, created_by, valid_from)
                VALUES (?, ?::vector, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?::timestamptz, now()))
                RETURNING id, wing, hall, room, status
                """,
                content, embeddingArray, wing, hall, room, source, tagArray, importance,
                summary, keyPointArray, insight, actionability, status, createdBy, validFrom);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", uuidValue(row, "id"));
        result.put("wing", row.get("wing", String.class));
        result.put("hall", row.get("hall", String.class));
        result.put("room", row.get("room", String.class));
        result.put("status", row.get("status", String.class));
        return result;
    }

    public int upsertIdentity(String key, String content, int tokenCount) {
        return dslContext.execute("""
                INSERT INTO identity (key, content, token_count, updated_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (key) DO UPDATE
                SET content = EXCLUDED.content,
                    token_count = EXCLUDED.token_count,
                    updated_at = now()
                """, key, content, tokenCount);
    }

    public Map<String, Object> addReference(
            String title,
            String url,
            String author,
            String refType,
            String status,
            String notes,
            List<String> tags,
            Integer importance
    ) {
        String[] tagArray = tags == null ? new String[0] : tags.toArray(String[]::new);
        Record row = dslContext.fetchOne("""
                INSERT INTO references_ (title, url, author, ref_type, status, notes, tags, importance)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id, title, status
                """, title, url, author, refType, status, notes, tagArray, importance);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", uuidValue(row, "id"));
        result.put("title", row.get("title", String.class));
        result.put("status", row.get("status", String.class));
        return result;
    }

    public Map<String, Object> linkReference(UUID drawerId, UUID referenceId, String relation) {
        Record row = dslContext.fetchOne("""
                INSERT INTO drawer_references (drawer_id, reference_id, relation)
                VALUES (?, ?, ?)
                RETURNING id
                """, drawerId, referenceId, relation);
        return Map.of(
                "id", uuidValue(row, "id"),
                "drawer_id", drawerId.toString(),
                "reference_id", referenceId.toString(),
                "relation", relation
        );
    }

    public Map<String, Object> registerAgent(
            String name,
            String focus,
            String autonomyJson,
            String schedule,
            String modelRoutingJson,
            List<String> tools
    ) {
        String[] toolArray = tools == null ? new String[0] : tools.toArray(String[]::new);
        String autonomyValue = autonomyJson == null ? "{\"default\":\"suggest_only\"}" : autonomyJson;
        Record row = dslContext.fetchOne("""
                INSERT INTO agents (name, focus, autonomy, schedule, model_routing, tools)
                VALUES (?, ?, COALESCE(?::jsonb, '{"default":"suggest_only"}'::jsonb), ?, ?::jsonb, ?)
                ON CONFLICT (name) DO UPDATE
                SET focus = EXCLUDED.focus,
                    autonomy = EXCLUDED.autonomy,
                    schedule = EXCLUDED.schedule,
                    model_routing = EXCLUDED.model_routing,
                    tools = EXCLUDED.tools
                RETURNING name, focus
                """, name, focus, autonomyValue, schedule, modelRoutingJson, toolArray);
        return Map.of(
                "name", row.get("name", String.class),
                "focus", row.get("focus", String.class)
        );
    }

    public Map<String, Object> diaryWrite(String agent, String entry) {
        Record row = dslContext.fetchOne("""
                INSERT INTO agent_diary (agent, entry)
                VALUES (?, ?)
                RETURNING id
                """, agent, entry);
        return Map.of(
                "id", uuidValue(row, "id"),
                "agent", agent
        );
    }

    public Map<String, Object> updateBlueprint(
            String createdBy,
            String wing,
            String title,
            String narrative,
            List<String> hallOrder,
            List<UUID> keyDrawers
    ) {
        return dslContext.transactionResult(configuration -> {
            DSLContext tx = DSL.using(configuration);
            tx.execute("SELECT pg_advisory_xact_lock(hashtext(?))", "blueprint:" + wing);
            OffsetDateTime timestamp = tx.fetchOne("SELECT now() AS ts").get("ts", OffsetDateTime.class);
            tx.execute("""
                    UPDATE blueprints
                    SET valid_until = ?::timestamptz
                    WHERE wing = ?
                      AND valid_until IS NULL
                    """, timestamp, wing);
            String[] hallOrderArray = hallOrder == null ? new String[0] : hallOrder.toArray(String[]::new);
            UUID[] keyDrawerArray = keyDrawers == null ? new UUID[0] : keyDrawers.toArray(UUID[]::new);
            Record row = tx.fetchOne("""
                    INSERT INTO blueprints (wing, title, narrative, hall_order, key_drawers, created_by, valid_from)
                    VALUES (?, ?, ?, ?, ?, ?, ?::timestamptz)
                    RETURNING id, wing, title
                    """, wing, title, narrative, hallOrderArray, keyDrawerArray, createdBy, timestamp);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", uuidValue(row, "id"));
            result.put("wing", row.get("wing", String.class));
            result.put("title", row.get("title", String.class));
            return result;
        });
    }

    public Map<String, Object> addTunnel(
            UUID fromDrawer,
            UUID toDrawer,
            String relation,
            String note,
            String status,
            String createdBy
    ) {
        Record row = dslContext.fetchOne("""
                INSERT INTO tunnels (from_drawer, to_drawer, relation, note, status, created_by)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id, from_drawer, to_drawer, relation, note, status
                """, fromDrawer, toDrawer, relation, note, status, createdBy);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", uuidValue(row, "id"));
        result.put("from_drawer", uuidValue(row, "from_drawer"));
        result.put("to_drawer", uuidValue(row, "to_drawer"));
        result.put("relation", row.get("relation", String.class));
        result.put("note", row.get("note", String.class));
        result.put("status", row.get("status", String.class));
        return result;
    }

    public int removeTunnel(UUID tunnelId) {
        return dslContext.execute("""
                UPDATE tunnels
                SET valid_until = now()
                WHERE id = ?
                  AND valid_until IS NULL
                """, tunnelId);
    }

    public int invalidateFact(UUID factId) {
        return dslContext.execute("""
                UPDATE facts
                SET valid_until = now()
                WHERE id = ?
                  AND valid_until IS NULL
                """, factId);
    }

    public Map<String, Object> reviseFact(
            UUID oldId,
            String newObject,
            String createdBy,
            String status
    ) {
        return dslContext.transactionResult(configuration -> {
            DSLContext tx = DSL.using(configuration);
            Record timestampRow = tx.fetchOne("SELECT now() AS ts");
            OffsetDateTime revisionTimestamp = timestampRow.get("ts", OffsetDateTime.class);
            Record oldRow = tx.fetchOne("""
                    SELECT subject, predicate, confidence, source_id
                    FROM facts
                    WHERE id = ? AND valid_until IS NULL
                    FOR UPDATE
                    """, oldId);
            if (oldRow == null) {
                throw new IllegalArgumentException("Fact " + oldId + " not found or already revised");
            }

            tx.execute("""
                    UPDATE facts
                    SET valid_until = ?::timestamptz
                    WHERE id = ?
                    """, revisionTimestamp, oldId);

            Record newRow = tx.fetchOne("""
                    INSERT INTO facts (parent_id, subject, predicate, "object", confidence, source_id, status, created_by, valid_from)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz)
                    RETURNING id
                    """,
                    oldId,
                    oldRow.get("subject", String.class),
                    oldRow.get("predicate", String.class),
                    newObject,
                    confidenceValue(oldRow),
                    oldRow.get("source_id", UUID.class),
                    status,
                    createdBy,
                    revisionTimestamp);
            return Map.of(
                    "old_id", oldId.toString(),
                    "new_id", uuidValue(newRow, "id")
            );
        });
    }

    public Map<String, Object> reviseDrawer(
            UUID oldId,
            String newContent,
            String newSummary,
            List<Float> embedding,
            String createdBy,
            String status
    ) {
        Float[] embeddingArray = embedding == null ? null : embedding.toArray(Float[]::new);
        return dslContext.transactionResult(configuration -> {
            DSLContext tx = DSL.using(configuration);
            Record timestampRow = tx.fetchOne("SELECT now() AS ts");
            OffsetDateTime revisionTimestamp = timestampRow.get("ts", OffsetDateTime.class);
            Record oldRow = tx.fetchOne("""
                    SELECT wing, hall, room, source, tags, importance, summary, key_points, insight, actionability
                    FROM drawers
                    WHERE id = ? AND valid_until IS NULL
                    FOR UPDATE
                    """, oldId);
            if (oldRow == null) {
                throw new IllegalArgumentException("Drawer " + oldId + " not found or already revised");
            }

            tx.execute("""
                    UPDATE drawers
                    SET valid_until = ?::timestamptz
                    WHERE id = ?
                    """, revisionTimestamp, oldId);

            Record newRow = tx.fetchOne("""
                    INSERT INTO drawers (parent_id, content, embedding, wing, hall, room, source, tags, importance,
                                         summary, key_points, insight, actionability, status, created_by, valid_from)
                    VALUES (?, ?, ?::vector, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz)
                    RETURNING id
                    """,
                    oldId,
                    newContent,
                    embeddingArray,
                    oldRow.get("wing", String.class),
                    oldRow.get("hall", String.class),
                    oldRow.get("room", String.class),
                    oldRow.get("source", String.class),
                    oldRow.get("tags", String[].class),
                    oldRow.get("importance", Integer.class),
                    newSummary == null ? oldRow.get("summary", String.class) : newSummary,
                    oldRow.get("key_points", String[].class),
                    oldRow.get("insight", String.class),
                    oldRow.get("actionability", String.class),
                    status,
                    createdBy,
                    revisionTimestamp
            );
            return Map.of(
                    "old_id", oldId.toString(),
                    "new_id", uuidValue(newRow, "id")
            );
        });
    }

    public List<Map<String, Object>> checkDuplicateDrawer(String vectorLiteral, double threshold) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch(
                "SELECT * FROM check_duplicate_drawer(?::vector, ?::real)",
                vectorLiteral, threshold)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", uuidValue(row, "id"));
            result.put("similarity", Math.round(row.get("similarity", Double.class) * 10_000.0d) / 10_000.0d);
            result.put("summary", row.get("summary", String.class));
            results.add(result);
        }
        return results;
    }

    public List<Map<String, Object>> checkContradiction(String subject, String predicate, String newObject) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch("""
                SELECT id AS fact_id, "object" AS existing_object, valid_from
                FROM active_facts
                WHERE subject = ?
                  AND predicate = ?
                  AND "object" <> ?
                """, subject, predicate, newObject)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("fact_id", uuidValue(row, "fact_id"));
            result.put("existing_object", row.get("existing_object", String.class));
            result.put("valid_from", defaultTimestampValue(row, "valid_from"));
            results.add(result);
        }
        return results;
    }

    public int approvePending(List<UUID> ids, String decision) {
        UUID[] idArray = ids.toArray(UUID[]::new);
        return dslContext.transactionResult(configuration -> {
            DSLContext tx = DSL.using(configuration);
            int drawerCount = tx.execute("""
                    UPDATE drawers
                    SET status = ?
                    WHERE id = ANY(?::uuid[])
                      AND status = 'pending'
                    """, decision, idArray);
            int factCount = tx.execute("""
                    UPDATE facts
                    SET status = ?
                    WHERE id = ANY(?::uuid[])
                      AND status = 'pending'
                    """, decision, idArray);
            int tunnelCount = tx.execute("""
                    UPDATE tunnels
                    SET status = ?
                    WHERE id = ANY(?::uuid[])
                      AND status = 'pending'
                    """, decision, idArray);
            return drawerCount + factCount + tunnelCount;
        });
    }

    private static Map<String, Object> factRow(Record row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", uuidValue(row, "id"));
        result.put("subject", row.get("subject", String.class));
        result.put("predicate", row.get("predicate", String.class));
        result.put("object", row.get("object", String.class));
        result.put("status", row.get("status", String.class));
        return result;
    }

    private static String uuidValue(Record row, String field) {
        UUID value = row.get(field, UUID.class);
        return value == null ? null : value.toString();
    }

    private static Double confidenceValue(Record row) {
        Number value = row.get("confidence", Number.class);
        return value == null ? null : value.doubleValue();
    }

    private static String timestampValue(Record row, String field) {
        OffsetDateTime value = row.get(field, OffsetDateTime.class);
        return value == null ? null : DateTimeFormatter.ISO_INSTANT.format(value.toInstant());
    }

    private static String defaultTimestampValue(Record row, String field) {
        OffsetDateTime value = row.get(field, OffsetDateTime.class);
        return value == null ? null : PYTHON_TIMESTAMP_FORMATTER.format(value);
    }
}
