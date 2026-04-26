package com.hivemem.sync;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Component
public class SyncOpsRepository {

    private static final int PAGE_LIMIT = 500;

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public SyncOpsRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    public List<OpDto> findOpsAfter(long since) {
        return dsl.fetch(
                "SELECT seq, op_id, op_type, payload, created_at FROM ops_log WHERE seq > ? ORDER BY seq LIMIT ?",
                since, PAGE_LIMIT)
                .map(this::toDto);
    }

    public OpDto findOpById(UUID opId) {
        Record row = dsl.fetchOne(
                "SELECT seq, op_id, op_type, payload, created_at FROM ops_log WHERE op_id = ?", opId);
        return row == null ? null : toDto(row);
    }

    private OpDto toDto(Record r) {
        JsonNode payload;
        try {
            Object raw = r.get("payload");
            String jsonStr = raw instanceof JSONB j ? j.data() : raw.toString();
            payload = objectMapper.readTree(jsonStr);
        } catch (Exception e) {
            payload = objectMapper.createObjectNode();
        }
        return new OpDto(
                r.get("seq", Long.class),
                r.get("op_id", UUID.class),
                r.get("op_type", String.class),
                payload,
                r.get("created_at", OffsetDateTime.class));
    }
}
