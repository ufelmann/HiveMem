package com.hivemem.backup;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.BiConsumer;

public class SeaweedFSDumper {

    private final S3Client s3;
    private final String bucket;

    public SeaweedFSDumper(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    /** Calls sink with (object metadata, inputStream) for every object. */
    public Stats dump(BiConsumer<S3Object, InputStream> sink) {
        long count = 0, bytes = 0;
        String continuation = null;
        do {
            ListObjectsV2Request.Builder req = ListObjectsV2Request.builder().bucket(bucket);
            if (continuation != null) req.continuationToken(continuation);
            var resp = s3.listObjectsV2(req.build());
            for (S3Object obj : resp.contents()) {
                try (InputStream in = s3.getObject(GetObjectRequest.builder()
                        .bucket(bucket).key(obj.key()).build())) {
                    sink.accept(obj, in);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to stream " + obj.key(), e);
                }
                count++;
                bytes += obj.size();
            }
            continuation = Boolean.TRUE.equals(resp.isTruncated()) ? resp.nextContinuationToken() : null;
        } while (continuation != null);
        return new Stats(count, bytes);
    }

    public record Stats(long objectCount, long totalBytes) {}
}
