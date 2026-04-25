package com.hivemem.sync;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class OpLogWriter {

    private final DSLContext dsl;
    private final InstanceConfig instanceConfig;
    private final ObjectMapper objectMapper;

    public OpLogWriter(DSLContext dsl, InstanceConfig instanceConfig, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.instanceConfig = instanceConfig;
        this.objectMapper = objectMapper;
    }

    public UUID append(String opType, Map<String, Object> payload) {
        UUID opId = UUID.randomUUID();
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("op-log payload is not JSON-serialisable", e);
        }
        dsl.execute(
                "INSERT INTO ops_log (instance_id, op_id, op_type, payload) "
                + "VALUES (?, ?, ?, ?::jsonb)",
                instanceConfig.instanceId(), opId, opType, payloadJson);
        return opId;
    }
}
