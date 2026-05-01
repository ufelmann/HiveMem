package com.hivemem.backup;

import java.io.IOException;
import java.io.InputStream;

public class PostgresRestorer {

    private final String psqlPath;

    public PostgresRestorer(String psqlPath) {
        this.psqlPath = psqlPath;
    }

    public void restore(String jdbcUrl, String user, String password, InputStream sql)
            throws IOException, InterruptedException {
        PostgresDumper.JdbcParts j = PostgresDumper.JdbcParts.parse(jdbcUrl);
        ProcessBuilder pb = new ProcessBuilder(
                psqlPath,
                "-v", "ON_ERROR_STOP=1",
                "--single-transaction",
                "-h", j.host(), "-p", String.valueOf(j.port()),
                "-U", user, "-d", j.database()
        );
        pb.environment().put("PGPASSWORD", password);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (var stdin = p.getOutputStream()) {
            sql.transferTo(stdin);
        }
        byte[] log = p.getInputStream().readAllBytes();
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("psql restore failed (exit " + code + "): "
                    + new String(log));
        }
    }
}
