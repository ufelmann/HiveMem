package com.hivemem.attachment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.net.URLConnection;
import java.nio.file.*;
import java.security.*;
import java.util.*;

@Service
public class AttachmentService {

    private final AttachmentProperties props;
    private final SeaweedFsClient seaweedFs;
    private final ParserRegistry parsers;
    private final AttachmentRepository repo;

    public AttachmentService(AttachmentProperties props, SeaweedFsClient seaweedFs,
                             ParserRegistry parsers, AttachmentRepository repo) {
        this.props = props;
        this.seaweedFs = seaweedFs;
        this.parsers = parsers;
        this.repo = repo;
    }

    @Transactional
    public Map<String, Object> ingest(InputStream inputStream, String originalFilename,
                                       String mimeType, UUID cellId, String uploadedBy) throws Exception {
        if (!props.isEnabled()) throw new IllegalStateException("Attachment storage is not enabled");

        Path tempFile = Files.createTempFile("hivemem-upload-", null);
        try {
            // 1. Stream to temp + compute SHA-256 in one pass
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream dis = new DigestInputStream(inputStream, digest)) {
                Files.copy(dis, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            long sizeBytes = Files.size(tempFile);

            // 2. Dedup check
            Optional<Map<String, Object>> existing = repo.findByHash(hash);
            if (existing.isPresent()) {
                UUID existingId = UUID.fromString((String) existing.get().get("id"));
                repo.linkToCell(existingId, cellId);
                Map<String, Object> result = new LinkedHashMap<>(existing.get());
                result.put("deduplicated", true);
                return result;
            }

            // 3. Upload original to SeaweedFS
            String ext = extensionFor(mimeType, originalFilename);
            String s3KeyOriginal = hash + "." + ext;
            seaweedFs.upload(s3KeyOriginal, tempFile, mimeType);

            // 4. Parse: extract text + thumbnail
            ParseResult parsed;
            try (InputStream s3Stream = seaweedFs.download(s3KeyOriginal)) {
                parsed = parsers.parse(mimeType, s3Stream);
            }

            // 5. Upload thumbnail if generated
            String s3KeyThumbnail = null;
            if (parsed.hasThumbnail()) {
                s3KeyThumbnail = hash + "-thumb.jpg";
                seaweedFs.uploadBytes(s3KeyThumbnail, parsed.thumbnail(), "image/jpeg");
            }

            // 6. Persist metadata
            Map<String, Object> row = repo.insert(
                    hash, mimeType, originalFilename, sizeBytes,
                    s3KeyOriginal, s3KeyThumbnail, parsed.extractedText(), uploadedBy);

            // 7. Link to cell
            UUID newId = UUID.fromString((String) row.get("id"));
            repo.linkToCell(newId, cellId);

            row.put("deduplicated", false);
            return row;

        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    public InputStream downloadOriginal(UUID id) {
        Map<String, Object> row = repo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Attachment not found: " + id));
        return seaweedFs.download((String) row.get("s3_key_original"));
    }

    public InputStream downloadThumbnail(UUID id) {
        Map<String, Object> row = repo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Attachment not found: " + id));
        String key = (String) row.get("s3_key_thumbnail");
        if (key == null) throw new NoSuchElementException("No thumbnail for attachment: " + id);
        return seaweedFs.download(key);
    }

    private String extensionFor(String mimeType, String filename) {
        if (filename != null && filename.contains(".")) {
            return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        }
        return switch (mimeType) {
            case "application/pdf"  -> "pdf";
            case "image/jpeg"       -> "jpg";
            case "image/png"        -> "png";
            case "image/gif"        -> "gif";
            case "image/webp"       -> "webp";
            case "message/rfc822"   -> "eml";
            default -> "bin";
        };
    }
}
