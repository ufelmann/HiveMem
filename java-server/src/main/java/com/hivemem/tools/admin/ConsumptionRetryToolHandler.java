package com.hivemem.tools.admin;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.consumption.ConsumptionProperties;
import com.hivemem.consumption.ConsumptionRetryService;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import com.hivemem.write.WriteArgumentParser;
import java.util.HashMap;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Re-stage one consumed file by content hash: locate the physical file in {@code failed/},
 * {@code processing/} or {@code processed/} and move it back to the watch root (mirrors
 * {@link com.hivemem.consumption.ConsumptionRecoverySweep#recover}, extended with the
 * {@code processed/} case for degraded batches that completed normally). Content-based dedup
 * (sha256 is UNIQUE) makes a re-run safe once {@code ConsumptionWatcher} re-hashes and re-stages
 * it.
 *
 * <p>{@link ConsumptionRetryService} and {@link ConsumptionProperties} are both unconditional
 * beans (unlike {@code ConsumptionQueueService}), so no {@code ObjectProvider} guard is needed for
 * Spring wiring here. But {@code ConsumptionWatcher} itself IS
 * {@code @ConditionalOnProperty(hivemem.consumption.enabled)} — if the pipeline is off, nothing
 * will ever pick the file back up from the watch root, so moving it there would silently strand
 * it. This handler checks {@link ConsumptionProperties#isEnabled()} before attempting a move and
 * returns the same "unavailable" shape as an unknown-hash miss instead.
 */
@Component
@Order(23)
public class ConsumptionRetryToolHandler implements ToolHandler {

    private final ConsumptionRetryService service;
    private final ConsumptionProperties props;

    public ConsumptionRetryToolHandler(ConsumptionRetryService service, ConsumptionProperties props) {
        this.service = service;
        this.props = props;
    }

    @Override public String name() { return "consumption_retry"; }

    @Override public String description() {
        return "Re-stage a consumed scan file by its sha256 so the pipeline ingests it again "
                + "(admin-only): moves the physical file from failed/, processing/ or processed/ "
                + "back to the watch root without touching the ledger row — the next poll re-hashes it and "
                + "re-stages it, resetting state and filename but keeping attempts, last_error and the "
                + "page-stat columns. Content-based dedup makes a re-run safe.";
    }

    @Override public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredString("sha256", "Content hash of the file to re-stage.")
                .build();
    }

    @Override public Object call(AuthPrincipal principal, JsonNode arguments) {
        String sha256 = WriteArgumentParser.requiredText(arguments, "sha256");
        if (!props.isEnabled()) {
            return Map.of("sha256", sha256, "restaged", false, "error", "consumption disabled");
        }
        ConsumptionRetryService.Result result = service.retry(sha256);
        Map<String, Object> out = new HashMap<>();
        out.put("sha256", result.sha256());
        out.put("restaged", result.restaged());
        if (result.error() != null) {
            out.put("error", result.error());
        }
        return out;
    }
}
