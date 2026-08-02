package com.hivemem.tools.read;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.consumption.ConsumptionQueueService;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import com.hivemem.write.WriteArgumentParser;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Ingest review queue: failed files, batches that lost vision metadata, and the reconciliation
 * counters accumulated since process start (see {@link ConsumptionQueueService}).
 *
 * <p>Registered unconditionally, unlike {@link ConsumptionQueueService} itself (which is
 * {@code @ConditionalOnProperty(hivemem.consumption.enabled)}): the handler is looked up via
 * {@link ObjectProvider} so the tool always exists on the MCP surface and degrades to an
 * "unavailable" response — mirroring {@code queen_runs}'s Vistierie-outage fallback — instead of
 * disappearing (and the README/tools.md tool count with it) whenever the consumption pipeline
 * happens to be off.
 */
@Component
@Order(22)
public class ConsumptionQueueToolHandler implements ToolHandler {

    private final ObjectProvider<ConsumptionQueueService> serviceProvider;

    public ConsumptionQueueToolHandler(ObjectProvider<ConsumptionQueueService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    @Override public String name() { return "consumption_queue"; }

    @Override public String description() {
        return "Scan ingest review queue (admin-only): failed files, batches that lost vision "
                + "metadata, and filesystem/ledger divergences found by the last reconciliation.";
    }

    @Override public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .optionalIntegerInRange("limit", "Max entries per section (default 50).", 1, 200)
                .build();
    }

    @Override public Object call(AuthPrincipal principal, JsonNode arguments) {
        ConsumptionQueueService service = serviceProvider.getIfAvailable();
        if (service == null) {
            return Map.of("files", java.util.List.of(), "degradedBatches", java.util.List.of(),
                    "reconciliation", Map.of(), "stateCounts", Map.of(), "unavailable", true);
        }
        Integer limit = WriteArgumentParser.optionalInteger(arguments, "limit");
        return service.queue(limit == null ? 50 : Math.min(Math.max(limit, 1), 200));
    }
}
