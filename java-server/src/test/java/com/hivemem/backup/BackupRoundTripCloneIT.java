package com.hivemem.backup;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(BackupRoundTripCloneIT.TestConfig.class)
class BackupRoundTripCloneIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean @Primary EmbeddingClient embeddingClient() { return new FixedEmbeddingClient(); }
    }

    @Container
    static final PostgreSQLContainer<?> SOURCE_DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    @Container
    static final GenericContainer<?> SOURCE_S3 = new GenericContainer<>(
            DockerImageName.parse("chrislusf/seaweedfs:3.68"))
            .withCommand("server -s3 -dir=/data").withExposedPorts(8333)
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))))
            .waitingFor(Wait.forHttp("/").forPort(8333)
                    .forStatusCodeMatching(c -> c == 400 || (c >= 200 && c < 500))
                    .withStartupTimeout(Duration.ofSeconds(120)));

    @Container
    static final PostgreSQLContainer<?> TARGET_DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    @Container
    static final GenericContainer<?> TARGET_S3 = new GenericContainer<>(
            DockerImageName.parse("chrislusf/seaweedfs:3.68"))
            .withCommand("server -s3 -dir=/data").withExposedPorts(8333)
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))))
            .waitingFor(Wait.forHttp("/").forPort(8333)
                    .forStatusCodeMatching(c -> c == 400 || (c >= 200 && c < 500))
                    .withStartupTimeout(Duration.ofSeconds(120)));

    @DynamicPropertySource
    static void wireSource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", SOURCE_DB::getJdbcUrl);
        r.add("spring.datasource.username", SOURCE_DB::getUsername);
        r.add("spring.datasource.password", SOURCE_DB::getPassword);
        r.add("hivemem.attachment.enabled", () -> "true");
        r.add("hivemem.attachment.s3-endpoint",
                () -> "http://" + SOURCE_S3.getHost() + ":" + SOURCE_S3.getMappedPort(8333));
    }

    @Autowired BackupService backup;

    @Test
    void cloneRoundTrip() throws Exception {
        // 1. Seed source: 2 cells via raw SQL
        try (Connection c = DriverManager.getConnection(
                SOURCE_DB.getJdbcUrl(), SOURCE_DB.getUsername(), SOURCE_DB.getPassword());
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO cells (id, content, embedding, realm, signal, topic, status, created_by, valid_from) "
                    + "VALUES "
                    + "(gen_random_uuid(), 'hello', array_fill(0::real, ARRAY[1024])::vector, 'test', 'facts', 'TestTopic', 'committed', 'test', now()),"
                    + "(gen_random_uuid(), 'world', array_fill(0::real, ARRAY[1024])::vector, 'test', 'facts', 'TestTopic', 'committed', 'test', now())");
        }
        UUID sourceInstanceId;
        try (Connection c = DriverManager.getConnection(
                SOURCE_DB.getJdbcUrl(), SOURCE_DB.getUsername(), SOURCE_DB.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT instance_id FROM instance_identity WHERE id = 1")) {
            rs.next();
            sourceInstanceId = (UUID) rs.getObject(1);
        }

        // 2. Export
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        Manifest m = backup.export(archive);
        assertEquals(2, m.counts().cells());
        assertEquals(sourceInstanceId, m.instanceId());

        // 3. Migrate target DB
        org.flywaydb.core.Flyway.configure()
                .dataSource(TARGET_DB.getJdbcUrl(), TARGET_DB.getUsername(), TARGET_DB.getPassword())
                .locations("classpath:db/migration")
                .load().migrate();

        // 4. Restore CLONE
        new BackupTestRestorer(TARGET_DB, TARGET_S3).restore(archive.toByteArray(), RestoreMode.CLONE);

        // 5. Assert: data preserved, identity rotated, sync state cleared
        try (Connection c = DriverManager.getConnection(
                TARGET_DB.getJdbcUrl(), TARGET_DB.getUsername(), TARGET_DB.getPassword());
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM cells")) {
                rs.next();
                assertEquals(2, rs.getInt(1));
            }
            try (ResultSet rs = st.executeQuery("SELECT instance_id FROM instance_identity WHERE id = 1")) {
                rs.next();
                UUID newId = (UUID) rs.getObject(1);
                assertNotEquals(sourceInstanceId, newId);
            }
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM ops_log")) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM sync_peers")) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM applied_ops")) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
        }
    }
}
