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
                "FROM attachments WHERE file_hash = ? AND deleted_at IS NULL",
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
            String extractedText, String uploadedBy) {
        Record row = dsl.fetchOne("""
                INSERT INTO attachments
                  (file_hash, mime_type, original_filename, size_bytes,
                   s3_key_original, s3_key_thumbnail, extracted_text, uploaded_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id, file_hash, mime_type, original_filename, size_bytes,
                          s3_key_original, s3_key_thumbnail, uploaded_by, created_at
                """,
                fileHash, mimeType, originalFilename, sizeBytes,
                s3KeyOriginal, s3KeyThumbnail, extractedText, uploadedBy);
        return toMap(row);
    }

    public void linkToCell(UUID attachmentId, UUID cellId) {
        Record refRow = dsl.fetchOne("""
                INSERT INTO references_ (title, url, ref_type, status)
                VALUES (?, ?, 'attachment', 'read')
                RETURNING id
                """,
                "attachment:" + attachmentId, "attachment:" + attachmentId);
        UUID refId = refRow.get("id", UUID.class);
        dsl.execute("""
                INSERT INTO cell_references (cell_id, reference_id, relation)
                VALUES (?, ?, 'attachment')
                """, cellId, refId);
    }

    public List<Map<String, Object>> findByCellId(UUID cellId) {
        return dsl.fetch("""
                SELECT a.id, a.file_hash, a.mime_type, a.original_filename,
                       a.size_bytes, a.s3_key_original, a.s3_key_thumbnail,
                       a.uploaded_by, a.created_at
                FROM attachments a
                JOIN references_ r ON r.url = 'attachment:' || a.id::text
                JOIN cell_references cr ON cr.reference_id = r.id
                WHERE cr.cell_id = ? AND a.deleted_at IS NULL
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
