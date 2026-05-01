package com.hivemem.backup;

import com.hivemem.attachment.AttachmentProperties;
import com.hivemem.attachment.SeaweedFsClient;
import org.jooq.DSLContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

@Service
public class BackupRestoreService {

    private final BackupProperties props;
    private final DSLContext dsl;
    private final SeaweedFsClient seaweed;
    private final String bucket;
    private final String dbJdbcUrl;
    private final String dbUser;
    private final String dbPassword;

    public BackupRestoreService(BackupProperties props,
                                DSLContext dsl,
                                SeaweedFsClient seaweed,
                                AttachmentProperties attachmentProps,
                                Environment env) {
        this.props = props;
        this.dsl = dsl;
        this.seaweed = seaweed;
        this.bucket = attachmentProps.getS3Bucket();
        this.dbJdbcUrl = env.getProperty("spring.datasource.url");
        this.dbUser = env.getProperty("spring.datasource.username");
        this.dbPassword = env.getProperty("spring.datasource.password");
    }

    /**
     * Restore an archive into the configured target database+bucket.
     *
     * @param mode  MOVE adopts the source identity; CLONE rotates it and clears sync state.
     * @param force when true, truncates target tables and bucket before import (otherwise refuses
     *              if target is non-empty).
     */
    public void restore(InputStream archiveIn, RestoreMode mode, boolean force)
            throws IOException, InterruptedException {

        // Phase-1 simplification: we read the entire archive into memory because manifest.json is
        // written last by BackupService (after counts are known) and we need to read it before
        // we can validate. For very large archives, switch to a temp-file approach with two passes.
        byte[] all = archiveIn.readAllBytes();
        Manifest manifest = readManifest(all);
        ManifestValidator.validateBasics(manifest);
        ManifestValidator.validateFlywayMatch(manifest, currentFlywayVersion());

        S3Client s3 = seaweed.s3Client();
        EmptinessCheck check = new EmptinessCheck(dsl, s3, bucket);
        SyncStateHandler sync = new SyncStateHandler(dsl);
        UUID currentId = sync.currentInstanceId();

        if (mode == RestoreMode.MOVE && currentId != null
                && !currentId.equals(manifest.instanceId()) && !force) {
            throw new IllegalStateException(
                    "MOVE refused: target instance_identity " + currentId
                    + " differs from manifest " + manifest.instanceId()
                    + ". Use --force to override.");
        }

        boolean dbHasData = !check.dbEmpty();
        boolean bucketHasData = !check.bucketEmpty();
        if ((dbHasData || bucketHasData) && !force) {
            throw new IllegalStateException(
                    "Restore refused: target is not empty (db="
                    + dbHasData + ", bucket=" + bucketHasData + "). Use --force.");
        }

        if (force) {
            truncateAllHivememTables();
            emptyBucket(s3);
        }

        // Stream entries: postgres.sql.gz → psql restore; attachments/* → S3 putObject.
        try (var br = new ArchiveReader(new ByteArrayInputStream(all))) {
            ArchiveReader.Entry e;
            while ((e = br.nextEntry()) != null) {
                if (e.name().equals("postgres.sql.gz")) {
                    try (GZIPInputStream gz = new GZIPInputStream(e.stream())) {
                        new PostgresRestorer(props.getPsqlPath())
                                .restore(dbJdbcUrl, dbUser, dbPassword, gz);
                    }
                } else if (e.name().startsWith("attachments/")) {
                    String key = e.name().substring("attachments/".length());
                    new SeaweedFSRestorer(s3, bucket).put(key, e.stream(), e.size());
                }
                // manifest.json is read in pass-1 above; ignore here.
            }
        }

        if (mode == RestoreMode.CLONE) {
            sync.applyClone();
        }
    }

    private Manifest readManifest(byte[] archive) throws IOException {
        try (var r = new ArchiveReader(new ByteArrayInputStream(archive))) {
            ArchiveReader.Entry e;
            while ((e = r.nextEntry()) != null) {
                if (e.name().equals("manifest.json")) {
                    String json = new String(e.read(), StandardCharsets.UTF_8);
                    return ManifestCodec.fromJson(json);
                }
            }
        }
        throw new IllegalStateException("manifest.json missing in archive");
    }

    private String currentFlywayVersion() {
        var rec = dsl.fetchOptional(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank DESC LIMIT 1");
        return rec.map(r -> "V" + r.get("version", String.class)).orElse("V0000");
    }

    private void truncateAllHivememTables() {
        // CASCADE on `cells` clears cell_attachments, tunnels (cells-to-cells fk), facts (cell-fk).
        // We then truncate the remaining roots that aren't reached via cascade.
        dsl.execute("TRUNCATE cells CASCADE");
        dsl.execute("TRUNCATE attachments CASCADE");
        dsl.execute("TRUNCATE facts CASCADE");
        dsl.execute("TRUNCATE ops_log RESTART IDENTITY CASCADE");
        dsl.execute("TRUNCATE applied_ops");
        dsl.execute("TRUNCATE sync_peers");
        dsl.execute("TRUNCATE sync_conflicts");
    }

    private void emptyBucket(S3Client s3) {
        var resp = s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build());
        for (var obj : resp.contents()) {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(obj.key()).build());
        }
    }
}
