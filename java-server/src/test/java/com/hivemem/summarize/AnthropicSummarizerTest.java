package com.hivemem.summarize;

import com.hivemem.extraction.ExtractionProfile;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AnthropicSummarizerTest {

    private static ExtractionProfile minimalProfile() {
        return new ExtractionProfile(
                "other", "default analysis prompt",
                List.of("topic"), List.of(), null, List.of());
    }

    @Test
    void summarizeParsesAnthropicResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        String responseJson = """
                {
                  "id": "msg_abc",
                  "type": "message",
                  "model": "claude-haiku-4-5-20251001",
                  "usage": { "input_tokens": 1234, "output_tokens": 56 },
                  "content": [
                    {
                      "type": "text",
                      "text": "{\\"summary\\":\\"Cell about widgets.\\",\\"key_points\\":[\\"Has 3 widgets\\",\\"Color is red\\"],\\"insight\\":\\"Widgets are fragile.\\",\\"tags\\":[\\"widgets\\",\\"red\\"]}"
                    }
                  ]
                }
                """;

        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", "test-key"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        AnthropicSummarizer s = new AnthropicSummarizer(
                builder, "test-key", "claude-haiku-4-5-20251001", 30, 8000, false);
        SummaryResult r = s.summarize("a long content body here", minimalProfile());

        assertEquals("Cell about widgets.", r.summary());
        assertEquals(List.of("Has 3 widgets", "Color is red"), r.keyPoints());
        assertEquals("Widgets are fragile.", r.insight());
        assertEquals(List.of("widgets", "red"), r.tags());
        assertEquals(1234, r.inputTokens());
        assertEquals(56, r.outputTokens());
        server.verify();
    }

    @Test
    void summarizeWithProfileParsesDocumentTypeAndFacts() {
        String anthropicResp = """
                {
                  "id":"msg_y","type":"message","model":"haiku",
                  "usage":{"input_tokens":100,"output_tokens":50},
                  "content":[{"type":"text","text":"{\\"summary\\":\\"Stadtwerke 234.56 EUR\\",\\"key_points\\":[],\\"insight\\":null,\\"tags\\":[\\"invoice\\"],\\"document_type\\":\\"invoice\\",\\"facts\\":[{\\"predicate\\":\\"vendor\\",\\"object\\":\\"Stadtwerke München\\",\\"confidence\\":0.98},{\\"predicate\\":\\"amount_total\\",\\"object\\":\\"234.56\\",\\"confidence\\":0.99}]}"}]
                }
                """;
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andRespond(withSuccess(anthropicResp, MediaType.APPLICATION_JSON));

        AnthropicSummarizer s = new AnthropicSummarizer(builder, "k", "haiku", 30, 8000, false);

        ExtractionProfile profile = new ExtractionProfile(
                "invoice", "extract invoice fields", List.of("vendor", "amount_total"),
                List.of(), null, List.of("invoice"));

        SummaryResult r = s.summarize("dummy content", profile);

        assertEquals("invoice", r.documentType());
        assertEquals(2, r.facts().size());
        assertEquals("vendor", r.facts().get(0).predicate());
        assertEquals("Stadtwerke München", r.facts().get(0).object());
        assertEquals(0.98, r.facts().get(0).confidence(), 0.001);
    }

    @Test
    void summarizeWithProfileTreatsMissingFactsAsEmpty() {
        String anthropicResp = """
                {
                  "usage":{"input_tokens":1,"output_tokens":1},
                  "content":[{"type":"text","text":"{\\"summary\\":\\"x\\",\\"key_points\\":[],\\"insight\\":null,\\"tags\\":[]}"}]
                }
                """;
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andRespond(withSuccess(anthropicResp, MediaType.APPLICATION_JSON));

        AnthropicSummarizer s = new AnthropicSummarizer(builder, "k", "haiku", 30, 8000, false);
        ExtractionProfile profile = new ExtractionProfile(
                "other", "p", List.of("topic"), List.of(), null, List.of());
        SummaryResult r = s.summarize("dummy", profile);

        assertNull(r.documentType());
        assertTrue(r.facts().isEmpty());
    }
}
