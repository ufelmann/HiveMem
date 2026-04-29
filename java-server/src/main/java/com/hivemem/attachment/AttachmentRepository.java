package com.hivemem.attachment;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class AttachmentRepository {

    private final DSLContext dsl;

    public AttachmentRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<Map<String, Object>> findByHash(String fileHash) {
        Record row = dsl.fetchOne(
                "SELECT id, file_hash, mime_type, original_filename, size_bytes, " +
                "s3_key_original, s3_key_thumbnail, uploaded_by, created_at " +
                "FROM attachments WHERE file_hash = ?",
                fileHash);
        return Optional.ofNullable(row).map(this::toMap);
    }

    public Optional<Map<String, Object>> findById(UUID id) {
        Record row = dsl.fetchOne(
                "SELECT id, file_hash, mime_type, original_filename, size_bytes, " +
                "s3_key_original, s3_key_thumbnail, uploaded_by, created_at " +
                "FROM attachments WHERE id = ? AND deleted_at IS NULL",
                id);
        return Optional.ofNullable(row).map(this::toMap);
    }

    public Map<String, Object> insert(
            String fileHash, String mimeType, String originalFilename,
            long sizeBytes, String s3KeyOriginal, String s3KeyThumbnail,
            String uploadedBy) {
        Record row = dsl.fetchOne("""
                INSERT INTO attachments
                  (file_hash, mime_type, original_filename, size_bytes,
                   s3_key_original, s3_key_thumbnail, uploaded_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING id, file_hash, mime_type, original_filename, size_bytes,
                          s3_key_original, s3_key_thumbnail, uploaded_by, created_at
                """,
                fileHash, mimeType, originalFilename, sizeBytes,
                s3KeyOriginal, s3KeyThumbnail, uploadedBy);
        return Optional.ofNullable(row)
                .map(this::toMap)
                .orElseThrow(() -> new NoSuchElementException("Attachment insert returned no row for hash: " + fileHash));
    }

    /** Idempotent: clears deleted_at if soft-deleted, updates thumbnail key if currently null. */
    public Map<String, Object> reactivate(UUID id, String s3KeyThumbnail) {
        Record row = dsl.fetchOne("""
                UPDATE attachments
                SET deleted_at = NULL,
                    s3_key_thumbnail = COALESCE(s3_key_thumbnail, ?)
                WHERE id = ?
                RETURNING id, file_hash, mime_type, original_filename, size_bytes,
                          s3_key_original, s3_key_thumbnail, uploaded_by, created_at
                """, s3KeyThumbnail, id);
        return Optional.ofNullable(row)
                .map(this::toMap)
                .orElseThrow(() -> new NoSuchElementException("Attachment not found for reactivation: " + id));
    }

    public void linkExtractionCell(UUID attachmentId, UUID cellId) {
        dsl.execute("""
                INSERT INTO cell_attachments (cell_id, attachment_id, extraction_source)
                VALUES (?, ?, true)
                ON CONFLICT (cell_id, attachment_id) DO NOTHING
                """, cellId, attachmentId);
    }

    public List<Map<String, Object>> findByCellId(UUID cellId) {
        return dsl.fetch("""
                SELECT a.id, a.file_hash, a.mime_type, a.original_filename,
                       a.size_bytes, a.s3_key_original, a.s3_key_thumbnail,
                       a.uploaded_by, a.created_at
                FROM attachments a
                JOIN cell_attachments ca ON ca.attachment_id = a.id
                WHERE ca.cell_id = ? AND a.deleted_at IS NULL
                ORDER BY a.created_at DESC
                """, cellId)
                .map(this::toMap);
    }

    public boolean softDelete(UUID id) {
        int updated = dsl.execute(
                "UPDATE attachments SET deleted_at = now() WHERE id = ? AND deleted_at IS NULL", id);
        return updated > 0;
    }

    private Map<String, Object> toMap(Record row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.get("id", UUID.class).toString());
        m.put("file_hash", row.get("file_hash", String.class));
        m.put("mime_type", row.get("mime_type", String.class));
        m.put("original_filename", row.get("original_filename", String.class));
        m.put("size_bytes", row.get("size_bytes", Long.class));
        m.put("s3_key_original", row.get("s3_key_original", String.class));
        m.put("s3_key_thumbnail", row.get("s3_key_thumbnail", String.class));
        m.put("uploaded_by", row.get("uploaded_by", String.class));
        m.put("created_at", row.get("created_at").toString());
        return m;
    }
}
