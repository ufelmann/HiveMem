package com.hivemem.backup;

import com.hivemem.attachment.AttachmentProperties;
import com.hivemem.attachment.SeaweedFsClient;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;

class BackupTestRestorer {

    private final PostgreSQLContainer<?> db;
    private final GenericContainer<?> s3;

    BackupTestRestorer(PostgreSQLContainer<?> db, GenericContainer<?> s3) {
        this.db = db;
        this.s3 = s3;
    }

    void restore(byte[] archive, RestoreMode mode) throws Exception {
        restore(archive, mode, false);
    }

    void restore(byte[] archive, RestoreMode mode, boolean force) throws Exception {
        DataSource ds = new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
        DSLContext dsl = DSL.using(ds, SQLDialect.POSTGRES);

        AttachmentProperties attachmentProps = new AttachmentProperties();
        attachmentProps.setEnabled(true);
        attachmentProps.setS3Endpoint("http://" + s3.getHost() + ":" + s3.getMappedPort(8333));
        attachmentProps.setS3Bucket("hivemem-attachments");
        attachmentProps.setS3AccessKey("hivemem");
        attachmentProps.setS3SecretKey("hivemem_secret");

        SeaweedFsClient seaweed = new SeaweedFsClient(attachmentProps);
        // SeaweedFsClient.init() is package-private @PostConstruct; invoke via reflection.
        Method init = SeaweedFsClient.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(seaweed);

        BackupProperties props = new BackupProperties();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.url", db.getJdbcUrl());
        env.setProperty("spring.datasource.username", db.getUsername());
        env.setProperty("spring.datasource.password", db.getPassword());

        new BackupRestoreService(props, dsl, seaweed, attachmentProps, env)
                .restore(new ByteArrayInputStream(archive), mode, force);
    }
}
