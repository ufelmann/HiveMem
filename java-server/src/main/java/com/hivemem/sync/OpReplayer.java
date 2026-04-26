package com.hivemem.sync;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.UUID;

@Component
public class OpReplayer {

    public enum ReplayResult { REPLAYED, SKIPPED, CONFLICT, UNKNOWN_OP }

    public record BatchResult(int replayed, int skipped) {}

    public ReplayResult replay(UUID sourcePeer, OpDto op) {
        return ReplayResult.SKIPPED;
    }

    public BatchResult replayAll(UUID sourcePeer, List<OpDto> ops) {
        int replayed = 0, skipped = 0;
        for (OpDto op : ops) {
            ReplayResult r = replay(sourcePeer, op);
            if (r == ReplayResult.REPLAYED) replayed++;
            else skipped++;
        }
        return new BatchResult(replayed, skipped);
    }
}
