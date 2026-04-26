package com.hivemem.sync;
import java.util.UUID;
public record SyncPeer(UUID peerUuid, String peerUrl, long lastSeenSeq, String outboundToken) {}
