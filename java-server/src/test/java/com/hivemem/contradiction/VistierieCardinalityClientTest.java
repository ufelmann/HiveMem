package com.hivemem.contradiction;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.hivemem.testsupport.MockVistierieServer;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Pins {@link VistierieCardinalityClient}'s dispatch contract: POST
 * /agents/predicate-cardinality-judge/run with {payload: {correlation_id, predicates},
 * completion_webhook, completion_webhook_token}, and the stop-signal classification of
 * 403/409/404 responses.
 *
 * <p>Uses the repo's WireMock-backed {@link MockVistierieServer} rather than {@code
 * MockRestServiceServer}: the client's package-private constructor installs an explicit {@code
 * SimpleClientHttpRequestFactory} on the builder, which would clobber the request factory {@code
 * MockRestServiceServer.bindTo} needs to install (see {@code VistierieAgentClientTriggerTest}).
 */
class VistierieCardinalityClientTest {

    private MockVistierieServer mock;
    private VistierieCardinalityClient client;

    @BeforeEach
    void up() {
        mock = new MockVistierieServer();
        mock.start();
        client = new VistierieCardinalityClient(
                RestClient.builder(), mock.baseUrl(), "tenant-tok", "http://hivemem:8080", "webhook-tok", 5);
    }

    @AfterEach
    void down() {
        mock.stop();
    }

    private PredicatePayload samplePredicate() {
        return new PredicatePayload("lives_in", List.of("Berlin", "Munich"));
    }

    @Test
    void dispatchPostsPredicatesAndReturnsRunId() {
        stubFor(post(urlEqualTo("/agents/predicate-cardinality-judge/run"))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"run_id\":\"run-def\",\"agent_name\":\"predicate-cardinality-judge\","
                                + "\"agent_version\":1,\"status\":\"queued\"}")));

        UUID correlationId = UUID.randomUUID();
        PredicatePayload predicate = samplePredicate();

        String runId = client.dispatch(correlationId, List.of(predicate));

        assertThat(runId).isEqualTo("run-def");
        verify(postRequestedFor(urlEqualTo("/agents/predicate-cardinality-judge/run"))
                .withHeader("Authorization", equalTo("Bearer tenant-tok"))
                // matchingJsonPath pins the actual nesting (payload.correlation_id,
                // payload.predicates[0].*), not just presence anywhere in the body.
                .withRequestBody(matchingJsonPath("$.payload.correlation_id", equalTo(correlationId.toString())))
                .withRequestBody(matchingJsonPath("$.payload.predicates[0].predicate", equalTo("lives_in")))
                .withRequestBody(matchingJsonPath("$.payload.predicates[0].sample_objects[0]", equalTo("Berlin")))
                .withRequestBody(matchingJsonPath("$.payload.predicates[0].sample_objects[1]", equalTo("Munich")))
                .withRequestBody(matchingJsonPath(
                        "$.completion_webhook", equalTo("http://hivemem:8080/vistierie/cardinality/done")))
                .withRequestBody(matchingJsonPath("$.completion_webhook_token", equalTo("webhook-tok"))));
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 409, 404})
    void dispatchThrowsDispatchRejectedOnStopSignalStatuses(int status) {
        stubFor(post(urlEqualTo("/agents/predicate-cardinality-judge/run"))
                .willReturn(aResponse().withStatus(status)));

        assertThatThrownBy(() -> client.dispatch(UUID.randomUUID(), List.of(samplePredicate())))
                .isInstanceOf(DispatchRejectedException.class)
                .satisfies(e -> assertThat(((DispatchRejectedException) e).status()).isEqualTo(status));
    }

    @Test
    void dispatchRethrowsServerErrorAsPlainRestClientException() {
        stubFor(post(urlEqualTo("/agents/predicate-cardinality-judge/run"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.dispatch(UUID.randomUUID(), List.of(samplePredicate())))
                .isInstanceOf(RestClientResponseException.class)
                .isNotInstanceOf(DispatchRejectedException.class);
    }

    @Test
    void dispatchThrowsResourceAccessExceptionWhenServerUnreachable() {
        // Port 1 is a valid but uncontactable target — the client's catch is typed to
        // RestClientResponseException, so a connect failure must fly past it as
        // ResourceAccessException, not be misclassified as DispatchRejectedException. This is the
        // "may have started" half of the stop-signal contract this whole feature depends on.
        VistierieCardinalityClient unreachable = new VistierieCardinalityClient(
                RestClient.builder(), "http://localhost:1", "tenant-tok", "http://hivemem:8080", "webhook-tok", 2);

        assertThatThrownBy(() -> unreachable.dispatch(UUID.randomUUID(), List.of(samplePredicate())))
                .isInstanceOf(ResourceAccessException.class)
                .isNotInstanceOf(DispatchRejectedException.class);
    }

    @Test
    void dispatchReturnsNullWhenResponseHasNoRunId() {
        stubFor(post(urlEqualTo("/agents/predicate-cardinality-judge/run"))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"agent_name\":\"predicate-cardinality-judge\",\"agent_version\":1,"
                                + "\"status\":\"queued\"}")));

        String runId = client.dispatch(UUID.randomUUID(), List.of(samplePredicate()));

        assertThat(runId).isNull();
    }

    @Test
    void requestBodyCarriesNoTimestampOrProvenanceFields() {
        stubFor(post(urlEqualTo("/agents/predicate-cardinality-judge/run"))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                                "{\"run_id\":\"run-def\",\"agent_name\":\"predicate-cardinality-judge\",\"agent_version\":1,\"status\":\"queued\"}")));

        client.dispatch(UUID.randomUUID(), List.of(samplePredicate()));

        List<ServeEvent> events = mock.allServeEvents();
        assertThat(events).hasSize(1);
        String body = events.get(0).getRequest().getBodyAsString();

        assertThat(body)
                .doesNotContainIgnoringCase("valid_from")
                .doesNotContainIgnoringCase("ingested_at")
                .doesNotContainIgnoringCase("valid_until")
                .doesNotContainIgnoringCase("confidence")
                .doesNotContainIgnoringCase("detected_at")
                .doesNotMatch("(?s).*\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}.*");
    }

    @Test
    void predicatePayloadCarriesNoMaxObjectsOrSampleSubjects() {
        stubFor(post(urlEqualTo("/agents/predicate-cardinality-judge/run"))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                                "{\"run_id\":\"run-def\",\"agent_name\":\"predicate-cardinality-judge\",\"agent_version\":1,\"status\":\"queued\"}")));

        client.dispatch(UUID.randomUUID(), List.of(samplePredicate()));

        List<ServeEvent> events = mock.allServeEvents();
        String body = events.get(0).getRequest().getBodyAsString();

        assertThat(body).doesNotContainIgnoringCase("max_objects").doesNotContainIgnoringCase("sample_subjects");
    }
}
