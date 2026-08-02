package com.hivemem.tools.admin;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.consumption.ConsumptionFileRepository;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import com.hivemem.write.WriteArgumentParser;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Re-stage one consumed file by content hash. Resetting the row to 'staged' is what makes the
 *  file eligible again: sha256 is UNIQUE, so without the reset a re-fed identical scan keeps its
 *  old terminal state and is skipped forever.
 *
 *  <p>{@link ConsumptionFileRepository} is an unconditional {@code @Repository} (the
 *  {@code consumption_file} table exists regardless of {@code hivemem.consumption.enabled}), so
 *  unlike {@code ConsumptionQueueToolHandler} this handler needs no availability guard — it is
 *  injected directly, the same way every other admin tool handler in this package is. */
@Component
@Order(23)
public class ConsumptionRetryToolHandler implements ToolHandler {

    private final ConsumptionFileRepository repo;

    public ConsumptionRetryToolHandler(ConsumptionFileRepository repo) {
        this.repo = repo;
    }

    @Override public String name() { return "consumption_retry"; }

    @Override public String description() {
        return "Re-stage a consumed scan file by its sha256 so the pipeline ingests it again "
                + "(admin-only). Content-based dedup makes a re-run safe.";
    }

    @Override public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredString("sha256", "Content hash of the file to re-stage.")
                .build();
    }

    @Override public Object call(AuthPrincipal principal, JsonNode arguments) {
        String sha256 = WriteArgumentParser.requiredText(arguments, "sha256");
        var row = repo.findByHash(sha256);
        if (row.isEmpty()) {
            return Map.of("sha256", sha256, "restaged", false, "error", "unknown sha256");
        }
        repo.stage(sha256, row.get().filename());
        return Map.of("sha256", sha256, "restaged", true);
    }
}
