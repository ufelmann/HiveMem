package com.hivemem.backup;

import org.jooq.DSLContext;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

public class EmptinessCheck {

    private final DSLContext dsl;
    private final S3Client s3;
    private final String bucket;

    public EmptinessCheck(DSLContext dsl, S3Client s3, String bucket) {
        this.dsl = dsl;
        this.s3 = s3;
        this.bucket = bucket;
    }

    public boolean dbEmpty() {
        Long count = dsl.fetchOne("SELECT count(*) FROM cells").get(0, Long.class);
        return count == 0;
    }

    public boolean bucketEmpty() {
        var resp = s3.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(bucket).maxKeys(1).build());
        return resp.contents().isEmpty();
    }
}
