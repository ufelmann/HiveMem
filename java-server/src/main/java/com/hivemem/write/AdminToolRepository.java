package com.hivemem.write;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class AdminToolRepository {

    private final DSLContext dslContext;

    public AdminToolRepository(DSLContext dslContext) {
        this.dslContext = dslContext;
    }

    public Map<String, Object> health() {
        Map<String, String> extensions = new LinkedHashMap<>();
        for (Record row : dslContext.fetch("""
                SELECT extname, extversion
                FROM pg_extension
                WHERE extname IN ('vector', 'age')
                ORDER BY extname
                """)) {
            extensions.put(row.get("extname", String.class), row.get("extversion", String.class));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("db_connected", dslContext.fetchOne("SELECT 1 AS ok").get("ok", Integer.class) == 1);
        result.put("extensions", extensions);
        result.put("cells", count("SELECT count(*) AS cnt FROM cells"));
        result.put("facts", count("SELECT count(*) AS cnt FROM facts"));
        result.put("db_size", dslContext.fetchOne("SELECT pg_size_pretty(pg_database_size(current_database())) AS size").get("size", String.class));
        result.put("disk_free_gb", diskFreeGb());
        return result;
    }

    public void logAccess(UUID cellId, UUID factId, String accessedBy) {
        dslContext.execute("""
                INSERT INTO access_log (cell_id, fact_id, accessed_by)
                VALUES (?, ?, ?)
                """, cellId, factId, accessedBy);
    }

    public long refreshPopularity() {
        dslContext.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY cell_popularity");
        return count("SELECT count(*) AS cnt FROM cell_popularity");
    }

    private long count(String sql) {
        Number value = dslContext.fetchOne(sql).get("cnt", Number.class);
        return value == null ? 0L : value.longValue();
    }

    private static double diskFreeGb() {
        try {
            FileStore fileStore = Files.getFileStore(Path.of("/"));
            return Math.round((fileStore.getUsableSpace() / (1024d * 1024d * 1024d)) * 100.0d) / 100.0d;
        } catch (IOException e) {
            return -1.0d;
        }
    }
}
