package com.hivemem.backup;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "hivemem.backup")
public class BackupProperties {

    private String pgDumpPath = "pg_dump";
    private String psqlPath = "psql";
    private String tempDir = System.getProperty("java.io.tmpdir") + "/hivemem-backup";
    private String exportDir = "/var/lib/hivemem/exports";
    private int exportRetentionCount = 5;

    public String getPgDumpPath() { return pgDumpPath; }
    public void setPgDumpPath(String v) { this.pgDumpPath = v; }
    public String getPsqlPath() { return psqlPath; }
    public void setPsqlPath(String v) { this.psqlPath = v; }
    public String getTempDir() { return tempDir; }
    public void setTempDir(String v) { this.tempDir = v; }
    public String getExportDir() { return exportDir; }
    public void setExportDir(String v) { this.exportDir = v; }
    public int getExportRetentionCount() { return exportRetentionCount; }
    public void setExportRetentionCount(int v) { this.exportRetentionCount = v; }
}
