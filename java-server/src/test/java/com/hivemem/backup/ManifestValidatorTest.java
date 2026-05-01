package com.hivemem.backup;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ManifestValidatorTest {

    private static Manifest valid() {
        return new Manifest(
                "1", "build", UUID.randomUUID(), Instant.now(), "V0025",
                new Manifest.Counts(0, 0, 0, 0),
                new Manifest.OpsLog(0, 0),
                new Manifest.SyncPeers(0),
                new Manifest.AppliedOps(0),
                new Manifest.Postgres("plain-gzip", "postgres.sql.gz", 0),
                new Manifest.Attachments("bucket", 0, 0));
    }

    @Test
    void rejectsUnsupportedSchemaVersion() {
        Manifest m = valid().withSchemaVersion("999");
        var ex = assertThrows(IllegalStateException.class,
                () -> ManifestValidator.validateBasics(m));
        assertTrue(ex.getMessage().contains("schema_version"));
    }

    @Test
    void rejectsMissingPostgresFilename() {
        Manifest m = new Manifest(
                "1", "build", UUID.randomUUID(), Instant.now(), "V0025",
                new Manifest.Counts(0, 0, 0, 0),
                new Manifest.OpsLog(0, 0),
                new Manifest.SyncPeers(0),
                new Manifest.AppliedOps(0),
                new Manifest.Postgres("plain-gzip", null, 0),
                new Manifest.Attachments("bucket", 0, 0));
        assertThrows(IllegalStateException.class,
                () -> ManifestValidator.validateBasics(m));
    }

    @Test
    void rejectsFlywayMismatch() {
        Manifest m = valid();
        var ex = assertThrows(IllegalStateException.class,
                () -> ManifestValidator.validateFlywayMatch(m, "V0024"));
        assertTrue(ex.getMessage().contains("V0025"));
        assertTrue(ex.getMessage().contains("V0024"));
    }

    @Test
    void acceptsFlywayMatch() {
        ManifestValidator.validateFlywayMatch(valid(), "V0025");
    }
}
