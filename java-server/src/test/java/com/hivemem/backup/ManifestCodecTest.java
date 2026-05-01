package com.hivemem.backup;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestCodecTest {

    @Test
    void roundTripPreservesAllFields() throws Exception {
        UUID id = UUID.randomUUID();
        Manifest original = new Manifest(
                "1",
                "abc123",
                id,
                Instant.parse("2026-05-01T12:00:00Z"),
                "V0025",
                new Manifest.Counts(12, 3, 8, 5),
                new Manifest.OpsLog(42L, 42L),
                new Manifest.SyncPeers(2),
                new Manifest.AppliedOps(7L),
                new Manifest.Postgres("plain-gzip", "postgres.sql.gz", 1024L),
                new Manifest.Attachments("hivemem-attachments", 3, 4096L)
        );

        String json = ManifestCodec.toJson(original);
        Manifest parsed = ManifestCodec.fromJson(json);

        assertEquals(original, parsed);
        assertTrue(json.contains("\"schema_version\""));
        assertTrue(json.contains("\"instance_id\""));
    }
}
