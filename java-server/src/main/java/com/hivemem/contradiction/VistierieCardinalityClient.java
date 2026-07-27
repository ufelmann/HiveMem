package com.hivemem.contradiction;

import com.hivemem.queen.AgentDefinitions;
import com.hivemem.queen.QueenProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Dispatches a batch of predicates to Vistierie's {@code predicate-cardinality-judge} agent.
 *
 * <p>Mirrors {@code VistierieSeparationClient}'s run-creation contract: POST
 * {@code /agents/{name}/run} with {@code {payload, completion_webhook, completion_webhook_token}}.
 * Vistierie echoes back only the run id (RunCreatedResponse.run_id); the correlation id rides
 * inside the free-form {@code payload} and is what HiveMem uses to join the eventual callback.
 */
@Component
@ConditionalOnProperty(name = "hivemem.contradiction.enabled", havingValue = "true")
public class VistierieCardinalityClient {

    private final RestClient client;
    private final String tenantToken;
    private final String callbackBaseUrl;
    private final String callbackToken;

    public VistierieCardinalityClient(RestClient.Builder builder, QueenProperties props) {
        this(
                builder,
                props.getVistierieBaseUrl(),
                props.getVistierieToken(),
                props.getHivememBaseUrl(),
                props.getContradictionWebhookToken(),
                props.getCallTimeoutSeconds());
    }

    VistierieCardinalityClient(
            RestClient.Builder builder,
            String baseUrl,
            String tenantToken,
            String callbackBaseUrl,
            String callbackToken,
            int timeoutSeconds) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(timeoutSeconds * 1000);
        rf.setReadTimeout(timeoutSeconds * 1000);
        this.client = builder.baseUrl(baseUrl).requestFactory(rf).build();
        this.tenantToken = tenantToken;
        this.callbackBaseUrl = callbackBaseUrl;
        this.callbackToken = callbackToken;
    }

    /**
     * Create a run on Vistierie's predicate-cardinality-judge agent and return its run id.
     *
     * @return the Vistierie run id, or {@code null} if the response carried none — the reconcile
     *     sweep owns jobs left in that state.
     * @throws DispatchRejectedException if Vistierie responded 403, 409 or 404, meaning the run
     *     was never created.
     */
    public String dispatch(UUID correlationId, List<PredicatePayload> predicates) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("correlation_id", correlationId.toString());
        payload.put("predicates", predicates);

        Map<String, Object> body = new HashMap<>();
        body.put("payload", payload);
        body.put("completion_webhook", callbackBaseUrl + "/vistierie/cardinality/done");
        body.put("completion_webhook_token", callbackToken);

        try {
            RunCreated created = client.post()
                    .uri("/agents/{name}/run", AgentDefinitions.CARDINALITY_JUDGE_NAME)
                    .header("Authorization", "Bearer " + tenantToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(RunCreated.class);
            return created == null ? null : created.run_id();
        } catch (RestClientResponseException e) {
            DispatchRejectedException.throwIfStopSignal(e);
            throw e;
        }
    }
}
