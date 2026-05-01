package com.hivemem.backup;

import java.io.IOException;
import java.io.OutputStream;

public class PostgresDumper {

    private final String pgDumpPath;

    public PostgresDumper(String pgDumpPath) {
        this.pgDumpPath = pgDumpPath;
    }

    public void dump(String jdbcUrl, String user, String password, OutputStream out)
            throws IOException, InterruptedException {
        JdbcParts j = JdbcParts.parse(jdbcUrl);
        ProcessBuilder pb = new ProcessBuilder(
                pgDumpPath,
                "--format=plain",
                "--no-owner",
                "--no-privileges",
                "--serializable-deferrable",
                "-h", j.host(), "-p", String.valueOf(j.port()),
                "-U", user, "-d", j.database()
        );
        pb.environment().put("PGPASSWORD", password);
        pb.redirectErrorStream(false);
        Process p = pb.start();
        try (var in = p.getInputStream()) {
            in.transferTo(out);
        }
        int code = p.waitFor();
        if (code != 0) {
            String err = new String(p.getErrorStream().readAllBytes());
            throw new IOException("pg_dump failed (exit " + code + "): " + err);
        }
    }

    record JdbcParts(String host, int port, String database) {
        static JdbcParts parse(String jdbcUrl) {
            // Format: jdbc:postgresql://host:port/db?params
            String s = jdbcUrl.replaceFirst("^jdbc:postgresql://", "");
            int slash = s.indexOf('/');
            String hostPort = s.substring(0, slash);
            String rest = s.substring(slash + 1);
            int q = rest.indexOf('?');
            String db = (q >= 0) ? rest.substring(0, q) : rest;
            int colon = hostPort.indexOf(':');
            String host = (colon >= 0) ? hostPort.substring(0, colon) : hostPort;
            int port = (colon >= 0) ? Integer.parseInt(hostPort.substring(colon + 1)) : 5432;
            return new JdbcParts(host, port, db);
        }
    }
}
