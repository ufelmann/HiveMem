package com.hivemem.backup;

import java.time.Instant;
import java.util.UUID;

public record Manifest(
        String schemaVersion,
        String hivememBuild,
        UUID instanceId,
        Instant createdAt,
        String flywayVersion,
        Counts counts,
        OpsLog opsLog,
        SyncPeers syncPeers,
        AppliedOps appliedOps,
        Postgres postgres,
        Attachments attachments
) {
    public record Counts(long cells, long attachments, long facts, long tunnels) {}
    public record OpsLog(long maxSeq, long entryCount) {}
    public record SyncPeers(long count) {}
    public record AppliedOps(long count) {}
    public record Postgres(String format, String filename, long uncompressedBytes) {}
    public record Attachments(String s3Bucket, long objectCount, long totalBytes) {}

    public static final String CURRENT_SCHEMA_VERSION = "1";

    public Manifest withSchemaVersion(String v) {
        return new Manifest(v, hivememBuild, instanceId, createdAt, flywayVersion,
                counts, opsLog, syncPeers, appliedOps, postgres, attachments);
    }
}
