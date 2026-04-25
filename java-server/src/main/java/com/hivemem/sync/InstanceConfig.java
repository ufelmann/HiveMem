package com.hivemem.sync;

import jakarta.annotation.PostConstruct;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class InstanceConfig {

    private final DSLContext dsl;
    private volatile UUID instanceId;

    public InstanceConfig(DSLContext dsl) {
        this.dsl = dsl;
    }

    @PostConstruct
    void initialize() {
        UUID existing = dsl.fetchOptional("SELECT instance_id FROM instance_identity WHERE id = 1")
                .map(r -> r.get("instance_id", UUID.class))
                .orElse(null);
        if (existing != null) {
            this.instanceId = existing;
            return;
        }
        UUID generated = UUID.randomUUID();
        dsl.execute(
                "INSERT INTO instance_identity (id, instance_id) VALUES (1, ?) "
                + "ON CONFLICT (id) DO NOTHING",
                generated);
        this.instanceId = dsl.fetchOne("SELECT instance_id FROM instance_identity WHERE id = 1")
                .get("instance_id", UUID.class);
    }

    public UUID instanceId() {
        return instanceId;
    }
}
