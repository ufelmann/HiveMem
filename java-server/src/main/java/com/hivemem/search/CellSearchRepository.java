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
public class CellSearchRepository {

    private final DSLContext dslContext;

    public CellSearchRepository(DSLContext dslContext) {
        this.dslContext = dslContext;
    }

    public List<SearchCandidate> searchCandidates(String realm, String signal, String topic) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT c.id, c.content, c.summary, c.realm, c.signal, c.topic, c.tags, c.importance, c.created_at,
                       c.valid_from,
                       c.embedding::real[] AS embedding,
                       COALESCE(cp.access_count, 0) AS access_count
                FROM cells c
                LEFT JOIN cell_popularity cp ON cp.cell_id = c.id
                WHERE c.status = 'committed'
                  AND (c.valid_until IS NULL OR c.valid_until > now())
                """);
        if (realm != null) {
            sql.append(" AND c.realm = ?");
            params.add(realm);
        }
        if (signal != null) {
            sql.append(" AND c.signal = ?");
            params.add(signal);
        }
        if (topic != null) {
            sql.append(" AND c.topic = ?");
            params.add(topic);
        }
        sql.append(" ORDER BY c.created_at DESC");

        List<SearchCandidate> results = new ArrayList<>();
        for (Record row : dslContext.fetch(sql.toString(), params.toArray())) {
            results.add(new SearchCandidate(
                    row.get("id", UUID.class),
                    row.get("content", String.class),
                    row.get("summary", String.class),
                    row.get("realm", String.class),
                    row.get("signal", String.class),
                    row.get("topic", String.class),
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
            String realm,
            String signal,
            String topic,
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
