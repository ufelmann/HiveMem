package com.hivemem.embedding;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EmbeddingBackfillServiceTest {

    @Test
    void backfillsMissingEmbeddings() {
        EmbeddingBackfillRepository repo = mock(EmbeddingBackfillRepository.class);
        EmbeddingClient client = mock(EmbeddingClient.class);
        EmbeddingMigrationService migrationService = mock(EmbeddingMigrationService.class);
        UUID id = UUID.randomUUID();

        when(migrationService.isReencodingActive()).thenReturn(false);
        when(client.maxChars()).thenReturn(8000);
        when(repo.findCellsMissingEmbedding(8000, 50)).thenReturn(List.of(id));
        when(repo.findSnapshot(id)).thenReturn(Optional.of(new EmbeddingBackfillRepository.Snapshot("text", null)));
        when(client.encodeForCell("text", null)).thenReturn(List.of(0.1f, 0.2f));

        EmbeddingBackfillService service = new EmbeddingBackfillService(repo, client, migrationService, 50);
        service.backfill();

        verify(repo).setEmbedding(eq(id), any(Float[].class));
    }

    @Test
    void stopsOnEmbeddingUnavailable() {
        EmbeddingBackfillRepository repo = mock(EmbeddingBackfillRepository.class);
        EmbeddingClient client = mock(EmbeddingClient.class);
        EmbeddingMigrationService migrationService = mock(EmbeddingMigrationService.class);
        UUID id = UUID.randomUUID();

        when(migrationService.isReencodingActive()).thenReturn(false);
        when(client.maxChars()).thenReturn(8000);
        when(repo.findCellsMissingEmbedding(8000, 50)).thenReturn(List.of(id));
        when(repo.findSnapshot(id)).thenReturn(Optional.of(new EmbeddingBackfillRepository.Snapshot("text", null)));
        when(client.encodeForCell(any(), any())).thenThrow(new EmbeddingUnavailableException("down", null));

        EmbeddingBackfillService service = new EmbeddingBackfillService(repo, client, migrationService, 50);
        service.backfill(); // must not throw

        verify(repo, never()).setEmbedding(any(), any());
    }

    @Test
    void skipsEntirelyWhileReencodingIsActive() {
        EmbeddingBackfillRepository repo = mock(EmbeddingBackfillRepository.class);
        EmbeddingClient client = mock(EmbeddingClient.class);
        EmbeddingMigrationService migrationService = mock(EmbeddingMigrationService.class);

        when(migrationService.isReencodingActive()).thenReturn(true);

        EmbeddingBackfillService service = new EmbeddingBackfillService(repo, client, migrationService, 50);
        service.backfill();

        verifyNoInteractions(repo, client);
    }
}
