package com.hivemem.attachment;

import java.util.UUID;

/** Published after attachment ingest when the cell is a Kroki-renderable diagram. */
public record ThumbnailRequestedEvent(
        UUID attachmentId,
        UUID cellId,
        String fileHash,
        String mimeType,
        String diagramSource
) {}
