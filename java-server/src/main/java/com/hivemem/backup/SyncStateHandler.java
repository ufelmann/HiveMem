package com.hivemem.backup;

import org.jooq.DSLContext;
import java.util.UUID;

/**
 * Applies multi-master sync semantics after a restore.
 *
 * MOVE preserves the source instance_id, ops_log, sync_peers, applied_ops verbatim.
 * CLONE rotates instance_id and truncates ops_log/sync_peers/applied_ops/sync_conflicts.
 * Caller is responsible for triggering OpLogBackfillRunner afterwards (it runs on next
 * Spring boot of the restored instance).
 */
public class SyncStateHandler {

    private final DSLContext dsl;

    public SyncStateHandler(DSLContext dsl) {
        this.dsl = dsl;
    }

    public UUID currentInstanceId() {
        var rec = dsl.fetchOptional("SELECT instance_id FROM instance_identity WHERE id = 1");
        return rec.map(r -> r.get("instance_id", UUID.class)).orElse(null);
    }

    public void applyClone() {
        UUID fresh = UUID.randomUUID();
        dsl.execute("UPDATE instance_identity SET instance_id = ? WHERE id = 1", fresh);
        dsl.execute("TRUNCATE ops_log RESTART IDENTITY CASCADE");
        dsl.execute("TRUNCATE sync_peers");
        dsl.execute("TRUNCATE applied_ops");
        dsl.execute("TRUNCATE sync_conflicts");
    }
}
