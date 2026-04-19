package com.hivemem.drawers;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DrawerReadRepository {

    private final DSLContext dslContext;

    public DrawerReadRepository(DSLContext dslContext) {
        this.dslContext = dslContext;
    }

    public Map<String, Object> statusSnapshot() {
        Record counts = dslContext.fetchOne("""
                SELECT
                    (SELECT count(*) FROM active_drawers) AS drawers,
                    (SELECT count(*) FROM active_facts) AS facts,
                    (SELECT count(*) FROM active_tunnels) AS tunnels,
                    (SELECT count(*) FROM pending_approvals) AS pending,
                    (SELECT max(created_at) FROM drawers) AS last_activity
                """);

        List<String> wings = dslContext.fetch("""
                        SELECT DISTINCT wing
                        FROM active_drawers
                        WHERE wing IS NOT NULL
                        ORDER BY wing
                        """)
                .map(record -> record.get("wing", String.class));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("drawers", countValue(counts, "drawers"));
        result.put("facts", countValue(counts, "facts"));
        result.put("tunnels", countValue(counts, "tunnels"));
        result.put("pending", countValue(counts, "pending"));
        result.put("last_activity", timestampValue(counts, "last_activity"));
        result.put("wings", wings);
        return result;
    }

    public List<Map<String, Object>> listWings() {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch("""
                SELECT wing,
                       count(DISTINCT hall) AS hall_count,
                       count(*) AS drawer_count
                FROM active_drawers
                WHERE wing IS NOT NULL
                GROUP BY wing
                ORDER BY wing
                """)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("wing", row.get("wing", String.class));
            result.put("hall_count", countValue(row, "hall_count"));
            result.put("drawer_count", countValue(row, "drawer_count"));
            results.add(result);
        }
        return results;
    }

    public List<Map<String, Object>> listHalls(String wing) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch("""
                SELECT hall, count(*) AS drawer_count
                FROM active_drawers
                WHERE wing = ? AND hall IS NOT NULL
                GROUP BY hall
                ORDER BY hall
                """, wing)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("hall", row.get("hall", String.class));
            result.put("drawer_count", countValue(row, "drawer_count"));
            results.add(result);
        }
        return results;
    }

    public List<Map<String, Object>> traverse(UUID drawerId, int maxDepth, String relationFilter) {
        String normalizedRelationFilter = relationFilter == null || relationFilter.isBlank() ? null : relationFilter;
        List<Object> params = new ArrayList<>();
        String sql;

        if (normalizedRelationFilter != null) {
            sql = """
                    WITH RECURSIVE
                    bidir AS (
                        SELECT from_drawer AS node, to_drawer AS neighbor, from_drawer, to_drawer, relation, note
                        FROM active_tunnels WHERE relation = ?
                        UNION ALL
                        SELECT to_drawer AS node, from_drawer AS neighbor, from_drawer, to_drawer, relation, note
                        FROM active_tunnels WHERE relation = ?
                    ),
                    graph AS (
                        SELECT from_drawer, to_drawer, relation, note, neighbor, 1 AS depth
                        FROM bidir
                        WHERE node = ?
                        UNION
                        SELECT b.from_drawer, b.to_drawer, b.relation, b.note, b.neighbor, g.depth + 1
                        FROM bidir b
                        JOIN graph g ON b.node = g.neighbor
                        WHERE g.depth < ?
                    )
                    SELECT DISTINCT from_drawer, to_drawer, relation, note, depth
                    FROM graph
                    ORDER BY depth, from_drawer
                    """;
            params.add(normalizedRelationFilter);
            params.add(normalizedRelationFilter);
            params.add(drawerId);
            params.add(maxDepth);
        } else {
            sql = """
                    WITH RECURSIVE
                    bidir AS (
                        SELECT from_drawer AS node, to_drawer AS neighbor, from_drawer, to_drawer, relation, note
                        FROM active_tunnels
                        UNION ALL
                        SELECT to_drawer AS node, from_drawer AS neighbor, from_drawer, to_drawer, relation, note
                        FROM active_tunnels
                    ),
                    graph AS (
                        SELECT from_drawer, to_drawer, relation, note, neighbor, 1 AS depth
                        FROM bidir
                        WHERE node = ?
                        UNION
                        SELECT b.from_drawer, b.to_drawer, b.relation, b.note, b.neighbor, g.depth + 1
                        FROM bidir b
                        JOIN graph g ON b.node = g.neighbor
                        WHERE g.depth < ?
                    )
                    SELECT DISTINCT from_drawer, to_drawer, relation, note, depth
                    FROM graph
                    ORDER BY depth, from_drawer
                    """;
            params.add(drawerId);
            params.add(maxDepth);
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch(sql, params.toArray())) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("from_drawer", uuidValue(row, "from_drawer"));
            result.put("to_drawer", uuidValue(row, "to_drawer"));
            result.put("relation", row.get("relation", String.class));
            result.put("note", row.get("note", String.class));
            result.put("depth", countValue(row, "depth"));
            results.add(result);
        }
        return results;
    }

    public List<Map<String, Object>> quickFacts(String entity) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch("""
                SELECT id, subject, predicate, "object", confidence, valid_from
                FROM active_facts
                WHERE subject = ? OR "object" = ?
                ORDER BY valid_from DESC
                """, entity, entity)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", uuidValue(row, "id"));
            result.put("subject", row.get("subject", String.class));
            result.put("predicate", row.get("predicate", String.class));
            result.put("object", row.get("object", String.class));
            result.put("confidence", numberValue(row, "confidence"));
            result.put("valid_from", timestampValue(row, "valid_from"));
            results.add(result);
        }
        return results;
    }

    public List<Map<String, Object>> timeMachine(String subject, OffsetDateTime asOf, OffsetDateTime asOfIngestion, int limit) {
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();

        if (asOf == null && asOfIngestion == null) {
            sql.append("""
                    SELECT id, subject, predicate, "object", confidence, valid_from, valid_until, ingested_at
                    FROM active_facts
                    WHERE subject ILIKE ?
                    ORDER BY valid_from DESC
                    LIMIT ?
                    """);
            params.add("%" + subject + "%");
            params.add(limit);
        } else {
            sql.append("""
                    SELECT id, subject, predicate, "object", confidence, valid_from, valid_until, ingested_at
                    FROM facts
                    WHERE subject ILIKE ?
                      AND status = 'committed'
                    """);
            params.add("%" + subject + "%");
            if (asOf != null) {
                sql.append("  AND valid_from <= ?::timestamptz\n");
                sql.append("  AND (valid_until IS NULL OR valid_until > ?::timestamptz)\n");
                params.add(asOf);
                params.add(asOf);
            }
            if (asOfIngestion != null) {
                sql.append("  AND ingested_at <= ?::timestamptz\n");
                params.add(asOfIngestion);
            }
            sql.append("ORDER BY valid_from DESC\n");
            sql.append("LIMIT ?\n");
            params.add(limit);
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch(sql.toString(), params.toArray())) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", uuidValue(row, "id"));
            result.put("subject", row.get("subject", String.class));
            result.put("predicate", row.get("predicate", String.class));
            result.put("object", row.get("object", String.class));
            result.put("confidence", numberValue(row, "confidence"));
            result.put("valid_from", timestampValue(row, "valid_from"));
            result.put("valid_until", timestampValue(row, "valid_until"));
            result.put("ingested_at", timestampValue(row, "ingested_at"));
            results.add(result);
        }
        return results;
    }

    public List<Map<String, Object>> drawerHistory(UUID drawerId) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch("""
                WITH RECURSIVE chain AS (
                    SELECT d.id, d.parent_id, d.summary, d.created_by, d.valid_from, d.valid_until, d.ingested_at, 1 AS depth
                    FROM drawers d
                    WHERE d.id = ?
                    UNION ALL
                    SELECT d.id, d.parent_id, d.summary, d.created_by, d.valid_from, d.valid_until, d.ingested_at, c.depth + 1
                    FROM drawers d
                    JOIN chain c ON d.id = c.parent_id
                    WHERE c.depth < 100
                )
                SELECT id, parent_id, summary, created_by, valid_from, valid_until, ingested_at
                FROM chain
                ORDER BY valid_from ASC
                """, drawerId)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", uuidValue(row, "id"));
            result.put("parent_id", uuidValue(row, "parent_id"));
            result.put("summary", row.get("summary", String.class));
            result.put("created_by", row.get("created_by", String.class));
            result.put("valid_from", timestampValue(row, "valid_from"));
            result.put("valid_until", timestampValue(row, "valid_until"));
            result.put("ingested_at", timestampValue(row, "ingested_at"));
            results.add(result);
        }
        return results;
    }

    public List<Map<String, Object>> factHistory(UUID factId) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch("""
                WITH RECURSIVE chain AS (
                    SELECT f.id, f.parent_id, f.subject, f.predicate, f."object", f.created_by, f.valid_from, f.valid_until, f.ingested_at, 1 AS depth
                    FROM facts f
                    WHERE f.id = ?
                    UNION ALL
                    SELECT f.id, f.parent_id, f.subject, f.predicate, f."object", f.created_by, f.valid_from, f.valid_until, f.ingested_at, c.depth + 1
                    FROM facts f
                    JOIN chain c ON f.id = c.parent_id
                    WHERE c.depth < 100
                )
                SELECT id, parent_id, subject, predicate, "object", created_by, valid_from, valid_until, ingested_at
                FROM chain
                ORDER BY valid_from ASC
                """, factId)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", uuidValue(row, "id"));
            result.put("parent_id", uuidValue(row, "parent_id"));
            result.put("subject", row.get("subject", String.class));
            result.put("predicate", row.get("predicate", String.class));
            result.put("object", row.get("object", String.class));
            result.put("created_by", row.get("created_by", String.class));
            result.put("valid_from", timestampValue(row, "valid_from"));
            result.put("valid_until", timestampValue(row, "valid_until"));
            result.put("ingested_at", timestampValue(row, "ingested_at"));
            results.add(result);
        }
        return results;
    }

    public List<Map<String, Object>> pendingApprovals() {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch("""
                SELECT type, id, description, wing, hall, created_by, created_at
                FROM pending_approvals
                ORDER BY created_at ASC
                """)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", row.get("type", String.class));
            result.put("id", uuidValue(row, "id"));
            result.put("description", row.get("description", String.class));
            result.put("wing", row.get("wing", String.class));
            result.put("hall", row.get("hall", String.class));
            result.put("created_by", row.get("created_by", String.class));
            result.put("created_at", timestampValue(row, "created_at"));
            results.add(result);
        }
        return results;
    }

    public List<Map<String, Object>> readingList(String refType, int limit) {
        String normalizedRefType = refType == null || refType.isBlank() ? null : refType;
        String sql = """
                SELECT r.id, r.title, r.url, r.author, r.ref_type, r.status, r.importance, r.created_at,
                       count(dr.id) AS linked_drawers
                FROM references_ r
                LEFT JOIN drawer_references dr ON dr.reference_id = r.id
                WHERE r.status IN ('unread', 'reading')
                """;
        Object[] params;
        if (normalizedRefType != null) {
            sql += " AND r.ref_type = ?\n";
            params = new Object[]{normalizedRefType, limit};
        } else {
            params = new Object[]{limit};
        }
        sql += """
                GROUP BY r.id
                ORDER BY r.importance ASC NULLS LAST, r.created_at DESC
                LIMIT ?
                """;

        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch(sql, params)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", uuidValue(row, "id"));
            result.put("title", row.get("title", String.class));
            result.put("url", row.get("url", String.class));
            result.put("author", row.get("author", String.class));
            result.put("ref_type", row.get("ref_type", String.class));
            result.put("status", row.get("status", String.class));
            result.put("importance", integerValue(row, "importance"));
            result.put("linked_drawers", countValue(row, "linked_drawers"));
            result.put("created_at", timestampValue(row, "created_at"));
            results.add(result);
        }
        return results;
    }

    public List<Map<String, Object>> listAgents() {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch("""
                SELECT name, focus, schedule, created_at
                FROM agents
                ORDER BY name
                """)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", row.get("name", String.class));
            result.put("focus", row.get("focus", String.class));
            result.put("schedule", row.get("schedule", String.class));
            result.put("created_at", timestampValue(row, "created_at"));
            results.add(result);
        }
        return results;
    }

    public List<Map<String, Object>> diaryRead(String agent, int lastN) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch("""
                SELECT id, agent, entry, created_at
                FROM agent_diary
                WHERE agent = ?
                ORDER BY created_at DESC
                LIMIT ?
                """, agent, lastN)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", uuidValue(row, "id"));
            result.put("agent", row.get("agent", String.class));
            result.put("entry", row.get("entry", String.class));
            result.put("created_at", timestampValue(row, "created_at"));
            results.add(result);
        }
        return results;
    }

    public List<Map<String, Object>> getBlueprint(String wing) {
        String normalizedWing = wing == null || wing.isBlank() ? null : wing;
        List<Map<String, Object>> results = new ArrayList<>();
        if (normalizedWing != null) {
            for (Record row : dslContext.fetch("""
                    SELECT id, wing, title, narrative, hall_order, key_drawers, created_by, valid_from
                    FROM active_blueprints
                    WHERE wing = ?
                    ORDER BY valid_from DESC
                    """, normalizedWing)) {
                results.add(blueprintRow(row));
            }
        } else {
            for (Record row : dslContext.fetch("""
                    SELECT id, wing, title, narrative, hall_order, key_drawers, created_by, valid_from
                    FROM active_blueprints
                    ORDER BY wing, valid_from DESC
                    """)) {
                results.add(blueprintRow(row));
            }
        }
        return results;
    }

    public Map<String, Object> wakeUp() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Record row : dslContext.fetch("""
                SELECT key, content, token_count
                FROM identity
                WHERE key IN ('l0_identity', 'l1_critical')
                ORDER BY key
                """)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("content", row.get("content", String.class));
            entry.put("token_count", integerValue(row, "token_count"));
            result.put(row.get("key", String.class), entry);
        }
        return result;
    }

    public Optional<Map<String, Object>> findDrawer(UUID drawerId) {
        Record row = dslContext.fetchOne("""
                SELECT id, parent_id, content, wing, hall, room, source, tags,
                       importance, summary, key_points, insight, actionability,
                       status, created_by, created_at, valid_from, valid_until
                FROM drawers
                WHERE id = ?
                """, drawerId);
        if (row == null) {
            return Optional.empty();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", uuidValue(row, "id"));
        result.put("parent_id", uuidValue(row, "parent_id"));
        result.put("content", row.get("content", String.class));
        result.put("wing", row.get("wing", String.class));
        result.put("hall", row.get("hall", String.class));
        result.put("room", row.get("room", String.class));
        result.put("source", row.get("source", String.class));
        result.put("tags", stringArrayValue(row, "tags"));
        result.put("importance", integerValue(row, "importance"));
        result.put("summary", row.get("summary", String.class));
        result.put("key_points", stringArrayValue(row, "key_points"));
        result.put("insight", row.get("insight", String.class));
        result.put("actionability", row.get("actionability", String.class));
        result.put("status", row.get("status", String.class));
        result.put("created_by", row.get("created_by", String.class));
        result.put("created_at", timestampValue(row, "created_at"));
        result.put("valid_from", timestampValue(row, "valid_from"));
        result.put("valid_until", timestampValue(row, "valid_until"));
        return Optional.of(result);
    }

    private static Map<String, Object> blueprintRow(Record row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", uuidValue(row, "id"));
        result.put("wing", row.get("wing", String.class));
        result.put("title", row.get("title", String.class));
        result.put("narrative", row.get("narrative", String.class));
        result.put("hall_order", stringArrayValue(row, "hall_order"));
        result.put("key_drawers", uuidArrayValue(row, "key_drawers"));
        result.put("created_by", row.get("created_by", String.class));
        result.put("valid_from", timestampValue(row, "valid_from"));
        return result;
    }

    private static long countValue(Record row, String field) {
        Number count = row.get(field, Number.class);
        return count == null ? 0L : count.longValue();
    }

    private static String uuidValue(Record row, String field) {
        UUID value = row.get(field, UUID.class);
        return value == null ? null : value.toString();
    }

    private static Integer integerValue(Record row, String field) {
        Number value = row.get(field, Number.class);
        return value == null ? null : value.intValue();
    }

    private static double numberValue(Record row, String field) {
        Number value = row.get(field, Number.class);
        return value == null ? 0.0d : value.doubleValue();
    }

    private static String timestampValue(Record row, String field) {
        OffsetDateTime value = row.get(field, OffsetDateTime.class);
        return value == null ? null : DateTimeFormatter.ISO_INSTANT.format(value.toInstant());
    }

    private static List<String> stringArrayValue(Record row, String field) {
        String[] values = row.get(field, String[].class);
        return values == null ? List.of() : List.copyOf(Arrays.asList(values));
    }

    private static List<String> uuidArrayValue(Record row, String field) {
        UUID[] values = row.get(field, UUID[].class);
        if (values == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>(values.length);
        for (UUID value : values) {
            result.add(value == null ? null : value.toString());
        }
        return List.copyOf(result);
    }
}
