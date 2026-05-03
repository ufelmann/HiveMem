package com.hivemem.backup;

import com.hivemem.attachment.AttachmentProperties;
import com.hivemem.attachment.SeaweedFsClient;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BackupRestoreServiceUnitTest {

    @Test
    void restoreThrowsWhenManifestMissingFromArchive() throws Exception {
        BackupRestoreService service = newService();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ArchiveWriter w = new ArchiveWriter(out)) {
            w.addEntry("postgres.sql.gz", new byte[]{1, 2, 3});
        }

        assertThatThrownBy(() -> service.restore(
                new ByteArrayInputStream(out.toByteArray()),
                RestoreMode.MOVE, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("manifest.json missing");
    }

    @Test
    void restoreThrowsWhenArchiveIsEmptyTar() throws Exception {
        BackupRestoreService service = newService();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ArchiveWriter w = new ArchiveWriter(out)) {
            // no entries at all
        }

        assertThatThrownBy(() -> service.restore(
                new ByteArrayInputStream(out.toByteArray()),
                RestoreMode.CLONE, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("manifest.json missing");
    }

    private static BackupRestoreService newService() {
        BackupProperties props = new BackupProperties();
        DSLContext dsl = mock(DSLContext.class);
        SeaweedFsClient seaweed = mock(SeaweedFsClient.class);
        AttachmentProperties attProps = new AttachmentProperties();
        Environment env = mock(Environment.class);
        when(env.getProperty("spring.datasource.url")).thenReturn("jdbc:postgresql://localhost/test");
        when(env.getProperty("spring.datasource.username")).thenReturn("test");
        when(env.getProperty("spring.datasource.password")).thenReturn("test");
        return new BackupRestoreService(props, dsl, seaweed, attProps, env);
    }
}
