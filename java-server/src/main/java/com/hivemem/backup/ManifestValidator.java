package com.hivemem.backup;

public final class ManifestValidator {

    private ManifestValidator() {}

    public static void validateBasics(Manifest m) {
        if (m == null) throw new IllegalStateException("manifest is null");
        if (!Manifest.CURRENT_SCHEMA_VERSION.equals(m.schemaVersion()))
            throw new IllegalStateException("Unsupported manifest schema_version: "
                    + m.schemaVersion() + " (expected " + Manifest.CURRENT_SCHEMA_VERSION + ")");
        if (m.flywayVersion() == null || m.flywayVersion().isBlank())
            throw new IllegalStateException("manifest flyway_version is missing");
        if (m.postgres() == null || m.postgres().filename() == null)
            throw new IllegalStateException("manifest postgres.filename is missing");
        if (m.attachments() == null || m.attachments().s3Bucket() == null)
            throw new IllegalStateException("manifest attachments.s3_bucket is missing");
        if (m.counts() == null)
            throw new IllegalStateException("manifest counts are missing");
    }

    public static void validateFlywayMatch(Manifest m, String currentDbFlywayVersion) {
        if (!m.flywayVersion().equals(currentDbFlywayVersion)) {
            throw new IllegalStateException(
                    "Flyway version mismatch: archive is " + m.flywayVersion()
                    + ", target DB is " + currentDbFlywayVersion
                    + ". Migrate the target DB to " + m.flywayVersion()
                    + " or use a matching archive.");
        }
    }
}
