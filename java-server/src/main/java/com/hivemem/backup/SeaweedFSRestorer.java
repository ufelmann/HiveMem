package com.hivemem.backup;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;

public class SeaweedFSRestorer {

    private final S3Client s3;
    private final String bucket;

    public SeaweedFSRestorer(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    public void put(String key, InputStream data, long size) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromInputStream(data, size));
    }
}
