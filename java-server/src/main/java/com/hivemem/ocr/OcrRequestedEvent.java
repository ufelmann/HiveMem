package com.hivemem.ocr;

import java.util.UUID;

public record OcrRequestedEvent(UUID cellId, UUID attachmentId, String s3Key) {}
