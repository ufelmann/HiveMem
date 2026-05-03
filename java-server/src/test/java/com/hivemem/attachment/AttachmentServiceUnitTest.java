package com.hivemem.attachment;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.write.WriteToolRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AttachmentServiceUnitTest {

    private final AttachmentProperties props = new AttachmentProperties();
    private final SeaweedFsClient seaweedFs = mock(SeaweedFsClient.class);
    private final ParserRegistry parsers = mock(ParserRegistry.class);
    private final AttachmentRepository repo = mock(AttachmentRepository.class);
    private final WriteToolRepository writeRepo = mock(WriteToolRepository.class);
    private final EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
    private final DSLContext dsl = mock(DSLContext.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final KrokiClient krokiClient = mock(KrokiClient.class);

    private AttachmentService service;

    @BeforeEach
    void setUp() {
        service = new AttachmentService(props, seaweedFs, parsers, repo, writeRepo,
                embeddingClient, dsl, events, krokiClient);
    }

    @Test
    void ingestThrowsWhenStorageDisabled() {
        props.setEnabled(false);
        InputStream in = new ByteArrayInputStream(new byte[]{1, 2, 3});
        assertThatThrownBy(() -> service.ingest(in, "x.txt", "text/plain", "r", "s", "t", null, "user"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Attachment storage is not enabled");
    }

    @Test
    void downloadOriginalThrowsWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.downloadOriginal(id))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    void downloadThumbnailThrowsWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.downloadThumbnail(id))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void downloadThumbnailThrowsWhenNoThumbnailKey() {
        UUID id = UUID.randomUUID();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id.toString());
        row.put("s3_key_original", "key.bin");
        row.put("s3_key_thumbnail", null);
        when(repo.findById(id)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.downloadThumbnail(id))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("No thumbnail");
    }

    @Test
    void downloadOriginalReturnsStream() {
        UUID id = UUID.randomUUID();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id.toString());
        row.put("s3_key_original", "key.bin");
        when(repo.findById(id)).thenReturn(Optional.of(row));
        InputStream stream = new ByteArrayInputStream(new byte[]{1, 2});
        when(seaweedFs.download("key.bin")).thenReturn(stream);

        assertThat(service.downloadOriginal(id)).isSameAs(stream);
    }

    @Test
    void downloadThumbnailReturnsStream() {
        UUID id = UUID.randomUUID();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id.toString());
        row.put("s3_key_thumbnail", "thumb.jpg");
        when(repo.findById(id)).thenReturn(Optional.of(row));
        InputStream stream = new ByteArrayInputStream(new byte[]{9});
        when(seaweedFs.download("thumb.jpg")).thenReturn(stream);

        assertThat(service.downloadThumbnail(id)).isSameAs(stream);
    }
}
