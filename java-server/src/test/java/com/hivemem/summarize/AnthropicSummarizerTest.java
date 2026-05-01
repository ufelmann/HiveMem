package com.hivemem.summarize;

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
        SummaryResult r = s.summarize("a long content body here");

        assertEquals("Cell about widgets.", r.summary());
        assertEquals(List.of("Has 3 widgets", "Color is red"), r.keyPoints());
        assertEquals("Widgets are fragile.", r.insight());
        assertEquals(List.of("widgets", "red"), r.tags());
        assertEquals(1234, r.inputTokens());
        assertEquals(56, r.outputTokens());
        server.verify();
    }
}
