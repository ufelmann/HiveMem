package com.hivemem.sync;

import jakarta.annotation.PostConstruct;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class OpLogBackfillRunner {

    private final DSLContext dsl;
    private final OpLogWriter opLogWriter;

    public OpLogBackfillRunner(DSLContext dsl, OpLogWriter opLogWriter) {
        this.dsl = dsl;
        this.opLogWriter = opLogWriter;
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
            return;
        }
        for (Record row : rows) {
            Map<String, Object> payload = new LinkedHashMap<>();
            for (var f : row.fields()) {
                Object value = row.get(f);
                if (value == null) continue;
                payload.put(f.getName(), value.toString());
            }
            opLogWriter.append(opType, payload);
        }
    }
}
