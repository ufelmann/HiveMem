package com.hivemem.backup;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeaweedFSDumperTest {

    private final S3Client s3 = mock(S3Client.class);

    @Test
    void dumpsAllObjectsAndAggregatesStats() {
        S3Object obj1 = S3Object.builder().key("a").size(10L).build();
        S3Object obj2 = S3Object.builder().key("b").size(25L).build();
        ListObjectsV2Response resp = ListObjectsV2Response.builder()
                .contents(obj1, obj2).isTruncated(false).build();
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(resp);
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenAnswer(inv -> stubResponseStream());

        List<String> seenKeys = new ArrayList<>();
        SeaweedFSDumper dumper = new SeaweedFSDumper(s3, "test-bucket");
        SeaweedFSDumper.Stats stats = dumper.dump((meta, in) -> seenKeys.add(meta.key()));

        assertThat(seenKeys).containsExactly("a", "b");
        assertThat(stats.objectCount()).isEqualTo(2);
        assertThat(stats.totalBytes()).isEqualTo(35L);
    }

    @Test
    void dumpFollowsPaginationViaContinuationToken() {
        S3Object obj1 = S3Object.builder().key("a").size(1L).build();
        S3Object obj2 = S3Object.builder().key("b").size(2L).build();
        ListObjectsV2Response page1 = ListObjectsV2Response.builder()
                .contents(obj1).isTruncated(true).nextContinuationToken("tok").build();
        ListObjectsV2Response page2 = ListObjectsV2Response.builder()
                .contents(obj2).isTruncated(false).build();

        when(s3.listObjectsV2(argThat((ListObjectsV2Request r) ->
                r != null && r.continuationToken() == null))).thenReturn(page1);
        when(s3.listObjectsV2(argThat((ListObjectsV2Request r) ->
                r != null && "tok".equals(r.continuationToken())))).thenReturn(page2);
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenAnswer(inv -> stubResponseStream());

        List<String> seen = new ArrayList<>();
        SeaweedFSDumper.Stats stats = new SeaweedFSDumper(s3, "b").dump((m, i) -> seen.add(m.key()));

        assertThat(seen).containsExactly("a", "b");
        assertThat(stats.objectCount()).isEqualTo(2);
        verify(s3, times(2)).listObjectsV2(any(ListObjectsV2Request.class));
    }

    @Test
    void dumpHandlesEmptyBucket() {
        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().isTruncated(false).build());
        Consumer<S3Object> sinkSpy = mock(Consumer.class);

        SeaweedFSDumper.Stats stats = new SeaweedFSDumper(s3, "b")
                .dump((m, in) -> sinkSpy.accept(m));

        assertThat(stats.objectCount()).isEqualTo(0);
        assertThat(stats.totalBytes()).isEqualTo(0);
        verify(sinkSpy, times(0)).accept(any());
    }

    private static ResponseInputStream<GetObjectResponse> stubResponseStream() {
        return new ResponseInputStream<>(GetObjectResponse.builder().build(),
                new ByteArrayInputStream(new byte[]{1, 2, 3}));
    }
}
