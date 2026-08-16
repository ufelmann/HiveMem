package com.hivemem.consumption;

import com.hivemem.queen.QueenProperties;
import com.hivemem.testsupport.MockVistierieServer;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

class CompleteClientToolTest {

    private MockVistierieServer mock;
    private CompleteClient client;

    @BeforeEach
    void up() {
        mock = new MockVistierieServer();
        mock.start();
        QueenProperties props = new QueenProperties();
        props.setVistierieBaseUrl(mock.baseUrl());
        props.setVistierieToken("tenant-tok");
        props.setDocumentSeparatorAgent("document-separator");
        props.setCallTimeoutSeconds(5);
        client = new CompleteClient(RestClient.builder(), props, new ConsumptionProperties());
    }

    @AfterEach
    void down() { mock.stop(); }

    private JsonNode call() {
        return client.completeWithTool("documents", "group these pages",
                "submit_mailings", "Deliver the grouping.", Map.of("type", "object"));
    }

    /** The raw body of the single request the client sent. */
    private String sentBody() {
        return mock.allServeEvents().get(0).getRequest().getBodyAsString();
    }

    @Test
    void sendsToolAndForcedChoiceAndReturnsToolInput() {
        mock.stubCompleteRaw("""
                {"text":null,"content_blocks":[
                  {"type":"tool_use","name":"submit_mailings",
                   "input":{"mailings":[{"mailing":"m1","pages":[1,2]}]}}]}
                """);

        JsonNode input = call();

        assertThat(input).isNotNull();
        assertThat(input.path("mailings").get(0).path("mailing").asString()).isEqualTo("m1");
        verify(postRequestedFor(urlEqualTo("/llm/complete")));
        assertThat(sentBody())
                .contains("\"tools\"")
                .contains("\"submit_mailings\"")
                .contains("\"tool_choice\"");
    }

    @Test
    void returnsNullWhenResponseCarriesNoToolUseBlock() {
        mock.stubCompleteRaw("{\"text\":\"some text\",\"content_blocks\":null}");
        assertThat(call()).isNull();
    }

    @Test
    void ignoresToolUseBlockWithADifferentName() {
        mock.stubCompleteRaw("""
                {"text":null,"content_blocks":[
                  {"type":"tool_use","name":"something_else","input":{"x":1}}]}
                """);
        assertThat(call()).isNull();
    }

    @Test
    void returnsNullWhenContentBlocksIsAStringNotAnArray() {
        mock.stubCompleteRaw("{\"text\":null,\"content_blocks\":\"oops, not an array\"}");
        assertThat(call()).isNull();
    }

    @Test
    void returnsNullWhenContentBlocksIsAnObjectNotAnArray() {
        mock.stubCompleteRaw("{\"text\":null,\"content_blocks\":{\"unexpected\":\"shape\"}}");
        assertThat(call()).isNull();
    }

    @Test
    void handsBackMalformedToolInputVerbatimWithoutRepairingIt() {
        // Real defect (2026-08-15): the gateway announces tool schemas but does not enforce them, so
        // a model can answer with "mailings" as a JSON STRING instead of an array. completeWithTool
        // must hand that back unrepaired so the caller can detect the bad shape and fall back.
        mock.stubCompleteRaw("""
                {"text":null,"content_blocks":[
                  {"type":"tool_use","name":"submit_mailings",
                   "input":{"mailings":"[{\\"mailing\\":\\"m1\\",\\"pages\\":[1,2]}]"}}]}
                """);

        JsonNode input = call();

        assertThat(input).isNotNull();
        JsonNode mailings = input.path("mailings");
        assertThat(mailings.isTextual()).isTrue();
        assertThat(mailings.isArray()).isFalse();
        assertThat(mailings.asString()).isEqualTo("[{\"mailing\":\"m1\",\"pages\":[1,2]}]");
    }
}
