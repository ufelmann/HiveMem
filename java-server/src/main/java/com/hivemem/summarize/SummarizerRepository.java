package com.hivemem.summarize;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SummarizerRepository {

    private final DSLContext dsl;

    public SummarizerRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<UUID> findCellsNeedingSummary(int limit) {
        var rows = dsl.fetch(
                "SELECT id FROM cells WHERE 'needs_summary' = ANY(tags) "
                + "AND status='active' "
                + "AND ('summarize_throttled' != ALL(tags) OR updated_at < now() - interval '15 minutes') "
                + "ORDER BY updated_at LIMIT ?", limit);
        List<UUID> ids = new ArrayList<>();
        for (Record r : rows) ids.add(r.get(0, UUID.class));
        return ids;
    }

    public Optional<CellSnapshot> findCellSnapshot(UUID id) {
        var rec = dsl.fetchOptional(
                "SELECT id, content, summary, key_points, insight, tags FROM cells WHERE id = ? AND status='active'", id);
        return rec.map(r -> new CellSnapshot(
                r.get("id", UUID.class),
                r.get("content", String.class),
                r.get("summary", String.class),
                arrayOrEmpty(r.get("key_points")),
                r.get("insight", String.class),
                arrayOrEmpty(r.get("tags"))
        ));
    }

    /** Mark a cell as needing a summary (idempotent). */
    public void tagNeedsSummary(UUID id) {
        dsl.execute(
                "UPDATE cells SET tags = "
                + "  CASE WHEN 'needs_summary' = ANY(tags) THEN tags ELSE array_append(tags, 'needs_summary') END "
                + "WHERE id = ?", id);
    }

    /** Tag a cell as throttled (rate-limited) to defer it for the next backfill. */
    public void tagThrottled(UUID id) {
        dsl.execute(
                "UPDATE cells SET tags = "
                + "  CASE WHEN 'summarize_throttled' = ANY(tags) THEN tags ELSE array_append(tags, 'summarize_throttled') END, "
                + "updated_at = now() "
                + "WHERE id = ?", id);
    }

    private static List<String> arrayOrEmpty(Object array) {
        if (array == null) return List.of();
        if (array instanceof java.sql.Array a) {
            try {
                Object[] inner = (Object[]) a.getArray();
                List<String> out = new ArrayList<>(inner.length);
                for (Object o : inner) out.add(String.valueOf(o));
                return out;
            } catch (Exception e) {
                return List.of();
            }
        }
        if (array instanceof Object[] arr) {
            List<String> out = new ArrayList<>(arr.length);
            for (Object o : arr) out.add(String.valueOf(o));
            return out;
        }
        return List.of();
    }

    public record CellSnapshot(
            UUID id,
            String content,
            String summary,
            List<String> keyPoints,
            String insight,
            List<String> tags
    ) {}
}
