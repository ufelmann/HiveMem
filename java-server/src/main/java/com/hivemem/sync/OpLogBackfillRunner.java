package com.hivemem.sync;

import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class OpLogBackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(OpLogBackfillRunner.class);

    private final DSLContext dsl;
    private final OpLogWriter opLogWriter;
    private final ObjectMapper objectMapper;

    public OpLogBackfillRunner(DSLContext dsl, OpLogWriter opLogWriter, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.opLogWriter = opLogWriter;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void onStartup() {
        runBackfill();
    }

    public void runBackfill() {
        long existing = dsl.fetchOne("SELECT count(*) AS c FROM ops_log").get("c", Long.class);
        if (existing > 0) {
            return;
        }

        backfillTable("cells", "add_cell");
        backfillTable("tunnels", "add_tunnel");
        backfillTable("facts", "kg_add");
        backfillTable("agents", "register_agent");
        backfillTable("references_", "add_reference");
        backfillTable("blueprints", "update_blueprint");
        backfillTable("agent_diary", "diary_write");
    }

    private void backfillTable(String tableName, String opType) {
        Result<Record> rows;
        try {
            rows = dsl.fetch("SELECT * FROM " + tableName);
        } catch (Exception e) {
            log.error("Backfill failed for table '{}' (op_type='{}')", tableName, opType, e);
            throw (e instanceof RuntimeException re) ? re : new RuntimeException(e);
        }
        for (Record row : rows) {
            Map<String, Object> payload = new LinkedHashMap<>();
            for (var f : row.fields()) {
                if ("embedding".equals(f.getName())) continue;
                Object value = row.get(f);
                if (value == null) continue;
                payload.put(f.getName(), toPayloadValue(f, value));
            }
            opLogWriter.append(opType, payload);
        }
    }

    private Object toPayloadValue(Field<?> field, Object value) {
        if (value instanceof String[] arr) return Arrays.asList(arr);
        if (value instanceof UUID[] arr) return Arrays.stream(arr).map(UUID::toString).toList();
        if (value instanceof Object[] arr) {
            return Arrays.stream(arr).map(e -> e instanceof UUID u ? u.toString() : e).toList();
        }
        if (value instanceof UUID uuid) return uuid.toString();
        if (value instanceof org.jooq.JSONB jsonb) {
            try {
                return objectMapper.readTree(jsonb.data());
            } catch (Exception e) {
                return jsonb.data();
            }
        }
        if (value instanceof String s && "jsonb".equalsIgnoreCase(field.getDataType().getCastTypeName())) {
            try {
                return objectMapper.readTree(s);
            } catch (Exception e) {
                return s;
            }
        }
        return value;
    }
}
