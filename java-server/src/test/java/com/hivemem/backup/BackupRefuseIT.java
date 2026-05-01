package com.hivemem.backup;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class BackupRefuseIT {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    @Container
    static final GenericContainer<?> S3 = new GenericContainer<>(
            DockerImageName.parse("chrislusf/seaweedfs:3.68"))
            .withCommand("server -s3 -dir=/data").withExposedPorts(8333)
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))))
            .waitingFor(Wait.forHttp("/").forPort(8333)
                    .forStatusCodeMatching(c -> c == 400 || (c >= 200 && c < 500))
                    .withStartupTimeout(Duration.ofSeconds(120)));

    /** Migrate target DB (idempotent — Flyway skips already-applied migrations). */
    private static void migrate() {
        org.flywaydb.core.Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .locations("classpath:db/migration")
                .load().migrate();
    }

    /** Build a minimal valid archive whose manifest carries the given Flyway version. */
    private static byte[] fakeArchive(String flywayVersion, UUID instanceId) throws Exception {
        Manifest m = new Manifest("1", "build", instanceId, Instant.now(),
                flywayVersion,
                new Manifest.Counts(0, 0, 0, 0),
                new Manifest.OpsLog(0, 0),
                new Manifest.SyncPeers(0),
                new Manifest.AppliedOps(0),
                new Manifest.Postgres("plain-gzip", "postgres.sql.gz", 0),
                new Manifest.Attachments("hivemem-attachments", 0, 0));

        // An "empty" gzipped postgres dump (no SQL statements)
        ByteArrayOutputStream gzBuf = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(gzBuf)) {
            gz.write("-- empty\n".getBytes(StandardCharsets.UTF_8));
        }

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ArchiveWriter w = new ArchiveWriter(archive)) {
            w.addEntry("postgres.sql.gz", gzBuf.toByteArray());
            w.addEntry("manifest.json", ManifestCodec.toJson(m).getBytes(StandardCharsets.UTF_8));
        }
        return archive.toByteArray();
    }

    @Test
    void refusesSchemaMismatch_evenWithForce() throws Exception {
        migrate();

        byte[] archive = fakeArchive("V9999", UUID.randomUUID());

        var ex = assertThrows(IllegalStateException.class,
                () -> new BackupTestRestorer(DB, S3).restore(archive, RestoreMode.MOVE, true));
        assertTrue(ex.getMessage().contains("V9999"),
                "Error should mention the archive version V9999, was: " + ex.getMessage());
    }

    @Test
    void refusesMoveAgainstDifferentIdentity_withoutForce() throws Exception {
        migrate();

        // Ensure an instance_identity row exists (normally seeded by InstanceConfig @PostConstruct,
        // which doesn't run in this test since we don't boot Spring).
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
             java.sql.Statement st = c.createStatement()) {
            st.execute("INSERT INTO instance_identity (id, instance_id) VALUES (1, gen_random_uuid()) ON CONFLICT DO NOTHING");
        }

        // Use the actual current Flyway version so the schema-version validator passes,
        // but a random instance UUID so the identity check fires.
        String currentFlyway = currentFlywayVersionOf(DB);
        byte[] archive = fakeArchive(currentFlyway, UUID.randomUUID());

        var ex = assertThrows(IllegalStateException.class,
                () -> new BackupTestRestorer(DB, S3).restore(archive, RestoreMode.MOVE, false));
        assertTrue(
                ex.getMessage().contains("MOVE refused")
                        || ex.getMessage().contains("instance"),
                "Error should mention MOVE/identity refusal, was: " + ex.getMessage());
    }

    private static String currentFlywayVersionOf(PostgreSQLContainer<?> db) throws Exception {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                db.getJdbcUrl(), db.getUsername(), db.getPassword());
             java.sql.Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery(
                     "SELECT version FROM flyway_schema_history WHERE success = true "
                     + "ORDER BY installed_rank DESC LIMIT 1")) {
            if (!rs.next()) return "V0000";
            return "V" + rs.getString(1);
        }
    }
}
