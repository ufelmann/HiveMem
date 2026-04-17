package com.hivemem.embedding;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class EmbeddingStateRepository {

    private final DSLContext dslContext;

    public EmbeddingStateRepository(DSLContext dslContext) {
        this.dslContext = dslContext;
    }

    public Optional<EmbeddingInfo> loadStoredInfo() {
        Record modelRow = dslContext.fetchOne(
                "SELECT content FROM identity WHERE key = ?", "embedding_model");
        Record dimRow = dslContext.fetchOne(
                "SELECT content FROM identity WHERE key = ?", "embedding_dimension");
        if (modelRow == null || dimRow == null) {
            return Optional.empty();
        }
        String model = modelRow.get("content", String.class);
        int dimension = Integer.parseInt(dimRow.get("content", String.class));
        return Optional.of(new EmbeddingInfo(model, dimension));
    }

    public void saveInfo(EmbeddingInfo info) {
        upsert("embedding_model", info.model());
        upsert("embedding_dimension", String.valueOf(info.dimension()));
    }

    public void saveProgress(int done, int total) {
        upsert("reencoding_progress", done + "/" + total);
    }

    public Optional<String> loadProgress() {
        Record row = dslContext.fetchOne(
                "SELECT content FROM identity WHERE key = ?", "reencoding_progress");
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(row.get("content", String.class));
    }

    public void clearProgress() {
        dslContext.execute("DELETE FROM identity WHERE key = ?", "reencoding_progress");
    }

    public int countDrawersWithContent() {
        Record row = dslContext.fetchOne(
                "SELECT count(*) AS cnt FROM drawers WHERE content IS NOT NULL AND status = 'committed'");
        return row == null ? 0 : row.get("cnt", Number.class).intValue();
    }

    public List<DrawerRow> fetchDrawerBatch(int offset, int batchSize) {
        return dslContext.fetch("""
                SELECT id, content FROM drawers
                WHERE content IS NOT NULL AND status = 'committed'
                ORDER BY created_at ASC
                LIMIT ? OFFSET ?
                """, batchSize, offset)
                .map(r -> new DrawerRow(r.get("id", UUID.class), r.get("content", String.class)));
    }

    public void updateEmbedding(UUID drawerId, List<Float> embedding) {
        Float[] embeddingArray = embedding.toArray(Float[]::new);
        dslContext.execute(
                "UPDATE drawers SET embedding = ?::vector WHERE id = ?",
                embeddingArray, drawerId);
    }

    public void dropEmbeddingIndex() {
        dslContext.execute("DROP INDEX IF EXISTS idx_drawers_embedding");
    }

    public void createEmbeddingIndex(int dimension) {
        dslContext.execute(
                "CREATE INDEX IF NOT EXISTS idx_drawers_embedding " +
                "ON drawers USING hnsw ((embedding::vector(" + dimension + ")) vector_cosine_ops)");
    }

    public boolean tryAdvisoryLock(long lockId) {
        Record row = dslContext.fetchOne("SELECT pg_try_advisory_lock(?) AS acquired", lockId);
        return row != null && Boolean.TRUE.equals(row.get("acquired", Boolean.class));
    }

    public void releaseAdvisoryLock(long lockId) {
        dslContext.execute("SELECT pg_advisory_unlock(?)", lockId);
    }

    private void upsert(String key, String content) {
        int tokenCount = content.length() / 4;
        dslContext.execute("""
                INSERT INTO identity (key, content, token_count, updated_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (key) DO UPDATE
                SET content = EXCLUDED.content,
                    token_count = EXCLUDED.token_count,
                    updated_at = now()
                """, key, content, tokenCount);
    }

    public record DrawerRow(UUID id, String content) {
    }
}
