package com.hivemem.sync;

import tools.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

public record OpDto(long seq, UUID opId, String opType, JsonNode payload, OffsetDateTime createdAt) {}
