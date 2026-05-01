package com.hivemem.attachment;

import java.util.UUID;

/** Published after attachment ingest when the cell is an image needing a description. */
public record VisionDescriptionRequestedEvent(
        UUID attachmentId,
        UUID cellId,
        String s3KeyOriginal,
        String mimeType
) {}
