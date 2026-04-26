package com.hivemem.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class PushDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PushDispatcher.class);

    private final SyncPeerRepository peerRepository;
    private final SyncOpsRepository syncOpsRepository;
    private final PeerClient peerClient;
    private final InstanceConfig instanceConfig;

    public PushDispatcher(SyncPeerRepository peerRepository, SyncOpsRepository syncOpsRepository,
                          PeerClient peerClient, InstanceConfig instanceConfig) {
        this.peerRepository = peerRepository;
        this.syncOpsRepository = syncOpsRepository;
        this.peerClient = peerClient;
        this.instanceConfig = instanceConfig;
    }

    @Async
    public void dispatch(UUID opId) {
        dispatchSync(opId);
    }

    void dispatchSync(UUID opId) {
        OpDto op = syncOpsRepository.findOpById(opId);
        if (op == null) {
            log.warn("PushDispatcher: op not found op_id={}", opId);
            return;
        }
        UUID myId = instanceConfig.instanceId();
        for (SyncPeer peer : peerRepository.findAllPeers()) {
            try {
                peerClient.pushOps(peer.peerUrl(), myId, List.of(op), peer.outboundToken());
            } catch (Exception e) {
                log.warn("Push failed peer={} op_id={}", peer.peerUrl(), opId, e);
            }
        }
    }
}
