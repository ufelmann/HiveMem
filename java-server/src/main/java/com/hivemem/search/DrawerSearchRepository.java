package com.hivemem.search;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Repository
public class DrawerSearchRepository {

    private final DSLContext dslContext;

    public DrawerSearchRepository(DSLContext dslContext) {
        this.dslContext = dslContext;
    }

    public List<SearchCandidate> searchCandidates(String wing, String hall, String room) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT d.id, d.content, d.summary, d.wing, d.hall, d.room, d.tags, d.importance, d.created_at,
                       d.valid_from,
                       d.embedding::real[] AS embedding,
                       COALESCE(dp.access_count, 0) AS access_count
                FROM drawers d
                LEFT JOIN drawer_popularity dp ON dp.drawer_id = d.id
                WHERE d.status = 'committed'
                  AND (d.valid_until IS NULL OR d.valid_until > now())
                """);
        if (wing != null) {
            sql.append(" AND d.wing = ?");
            params.add(wing);
        }
        if (hall != null) {
            sql.append(" AND d.hall = ?");
            params.add(hall);
        }
        if (room != null) {
            sql.append(" AND d.room = ?");
            params.add(room);
        }
        sql.append(" ORDER BY d.created_at DESC");

        List<SearchCandidate> results = new ArrayList<>();
        for (Record row : dslContext.fetch(sql.toString(), params.toArray())) {
            results.add(new SearchCandidate(
                    row.get("id", UUID.class),
                    row.get("content", String.class),
                    row.get("summary", String.class),
                    row.get("wing", String.class),
                    row.get("hall", String.class),
                    row.get("room", String.class),
                    textArray(row, "tags"),
                    row.get("importance", Integer.class),
                    row.get("created_at", OffsetDateTime.class),
                    row.get("valid_from", OffsetDateTime.class),
                    floatArray(row, "embedding"),
                    longValue(row, "access_count")
            ));
        }
        return results;
    }

    public record SearchCandidate(
            UUID id,
            String content,
            String summary,
            String wing,
            String hall,
            String room,
            List<String> tags,
            Integer importance,
            OffsetDateTime createdAt,
            OffsetDateTime validFrom,
            List<Float> embedding,
            long accessCount
    ) {
    }

    private static List<String> textArray(Record row, String field) {
        String[] values = row.get(field, String[].class);
        return values == null ? List.of() : Arrays.asList(values);
    }

    private static List<Float> floatArray(Record row, String field) {
        Float[] values = row.get(field, Float[].class);
        if (values == null) {
            return null;
        }
        return Arrays.asList(values);
    }

    private static long longValue(Record row, String field) {
        Number value = row.get(field, Number.class);
        return value == null ? 0L : value.longValue();
    }
}
