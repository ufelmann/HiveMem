package com.hivemem.backup;

import com.hivemem.attachment.AttachmentProperties;
import com.hivemem.attachment.SeaweedFsClient;
import org.jooq.DSLContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

@Service
public class BackupService {

    private final BackupProperties props;
    private final DSLContext dsl;
    private final SeaweedFsClient seaweed;
    private final String bucket;
    private final String dbJdbcUrl;
    private final String dbUser;
    private final String dbPassword;

    public BackupService(BackupProperties props,
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
     * Streams a complete archive of this instance to {@code archiveOut} and returns the manifest.
     *
     * <p>Layout:
     * <pre>
     *   postgres.sql.gz       (gzipped pg_dump output, written first)
     *   attachments/&lt;key&gt;    (one entry per S3 object)
     *   manifest.json         (last — counts/stats are now known)
     * </pre>
     *
     * <p>Note: {@code postgres.sql.gz} is buffered in memory because TAR requires the entry size
     * up front. For typical instances (&lt; 2 GB DB) this is fine; for very large instances,
     * swap to a temp-file approach using {@link BackupProperties#getTempDir()}.
     */
    public Manifest export(OutputStream archiveOut) throws IOException, InterruptedException {
        UUID instanceId = currentInstanceId();
        String flywayVersion = currentFlywayVersion();
        Manifest.Counts counts = readCounts();
        Manifest.OpsLog opsLog = readOpsLogStats();
        long peerCount = scalar("SELECT count(*) FROM sync_peers");
        long appliedCount = scalar("SELECT count(*) FROM applied_ops");

        try (ArchiveWriter archive = new ArchiveWriter(archiveOut)) {
            byte[] dumpGz = dumpPostgresGzipped();
            archive.addEntry("postgres.sql.gz", dumpGz);

            SeaweedFSDumper.Stats stats = new SeaweedFSDumper(seaweed.s3Client(), bucket).dump((obj, in) -> {
                try {
                    archive.addEntry("attachments/" + obj.key(), in, obj.size());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            Manifest manifest = new Manifest(
                    Manifest.CURRENT_SCHEMA_VERSION,
                    System.getProperty("hivemem.build", "unknown"),
                    instanceId,
                    Instant.now(),
                    flywayVersion,
                    counts,
                    opsLog,
                    new Manifest.SyncPeers(peerCount),
                    new Manifest.AppliedOps(appliedCount),
                    new Manifest.Postgres("plain-gzip", "postgres.sql.gz", dumpGz.length),
                    new Manifest.Attachments(bucket, stats.objectCount(), stats.totalBytes())
            );

            archive.addEntry("manifest.json",
                    ManifestCodec.toJson(manifest).getBytes(StandardCharsets.UTF_8));
            return manifest;
        }
    }

    private byte[] dumpPostgresGzipped() throws IOException, InterruptedException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            new PostgresDumper(props.getPgDumpPath())
                    .dump(dbJdbcUrl, dbUser, dbPassword, gz);
        }
        return bytes.toByteArray();
    }

    private UUID currentInstanceId() {
        return dsl.fetchOptional("SELECT instance_id FROM instance_identity WHERE id = 1")
                .map(r -> r.get("instance_id", UUID.class)).orElse(null);
    }

    private String currentFlywayVersion() {
        var rec = dsl.fetchOptional(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank DESC LIMIT 1");
        return rec.map(r -> "V" + r.get("version", String.class)).orElse("V0000");
    }

    private Manifest.Counts readCounts() {
        return new Manifest.Counts(
                scalar("SELECT count(*) FROM cells"),
                scalar("SELECT count(*) FROM attachments"),
                scalar("SELECT count(*) FROM facts"),
                scalar("SELECT count(*) FROM tunnels")
        );
    }

    private Manifest.OpsLog readOpsLogStats() {
        long max = scalar("SELECT COALESCE(MAX(seq), 0) FROM ops_log");
        long count = scalar("SELECT count(*) FROM ops_log");
        return new Manifest.OpsLog(max, count);
    }

    private long scalar(String sql) {
        Object v = dsl.fetchOne(sql).get(0);
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString());
    }
}
