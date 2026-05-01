package com.hivemem.backup;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
class PostgresDumpRestoreIT {

    @Container
    static final PostgreSQLContainer<?> SOURCE = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("src").withUsername("u").withPassword("p")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    @Container
    static final PostgreSQLContainer<?> TARGET = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("tgt").withUsername("u").withPassword("p")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    @Test
    void dumpAndRestoreRoundTrip() throws Exception {
        // PostgresDumper uses --data-only; schema must exist on both sides (Flyway is
        // responsible in production). Mirror that here by creating the table on each.
        try (Connection c = DriverManager.getConnection(
                SOURCE.getJdbcUrl(), SOURCE.getUsername(), SOURCE.getPassword());
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t (id INT PRIMARY KEY, name TEXT)");
            st.execute("INSERT INTO t VALUES (1,'foo'),(2,'bar')");
        }
        try (Connection c = DriverManager.getConnection(
                TARGET.getJdbcUrl(), TARGET.getUsername(), TARGET.getPassword());
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t (id INT PRIMARY KEY, name TEXT)");
        }

        ByteArrayOutputStream dump = new ByteArrayOutputStream();
        new PostgresDumper("pg_dump").dump(SOURCE.getJdbcUrl(),
                SOURCE.getUsername(), SOURCE.getPassword(), dump);

        new PostgresRestorer("psql").restore(TARGET.getJdbcUrl(),
                TARGET.getUsername(), TARGET.getPassword(),
                new ByteArrayInputStream(dump.toByteArray()));

        try (Connection c = DriverManager.getConnection(
                TARGET.getJdbcUrl(), TARGET.getUsername(), TARGET.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM t")) {
            rs.next();
            assertEquals(2, rs.getInt(1));
        }
    }
}
