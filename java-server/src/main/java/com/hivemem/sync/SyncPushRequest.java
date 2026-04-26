package com.hivemem.sync;

import java.util.List;
import java.util.UUID;

public record SyncPushRequest(UUID sourcePeer, List<OpDto> ops) {}
