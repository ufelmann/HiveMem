package com.hivemem.attachment;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class VisionClientTest {

    private AttachmentProperties propsWith(String key) {
        AttachmentProperties p = new AttachmentProperties();
        p.setAnthropicApiKey(key);
        p.setVisionTimeoutSeconds(5);
        p.setVisionModel("claude-haiku-4-5-20251001");
        p.setVisionMaxInputBytes(1024 * 1024);
        return p;
    }

    @Test
    void notEnabledWhenKeyBlank() {
        VisionClient c = new VisionClient(propsWith(""), RestClient.builder(), false);
        assertFalse(c.isEnabled());
    }

    @Test
    void describeReturnsTextOn200() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        String resp = """
                {
                  "usage":{"input_tokens":120,"output_tokens":40},
                  "content":[{"type":"text","text":"A photograph of a whiteboard with notes."}]
                }
                """;
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andRespond(withSuccess(resp, MediaType.APPLICATION_JSON));

        VisionClient c = new VisionClient(propsWith("k"), b, false);
        VisionClient.VisionResult r = c.describe(new byte[]{1, 2, 3, 4}, "image/png");

        assertEquals("A photograph of a whiteboard with notes.", r.description());
        assertEquals(120, r.inputTokens());
        assertEquals(40, r.outputTokens());
    }

    @Test
    void describeRejectsOversizeImage() {
        VisionClient c = new VisionClient(propsWith("k"), RestClient.builder(), false);
        byte[] tooBig = new byte[2 * 1024 * 1024];
        assertThrows(VisionClient.OversizeImageException.class,
                () -> c.describe(tooBig, "image/png"));
    }

    @Test
    void describeRejectsUnsupportedMime() {
        VisionClient c = new VisionClient(propsWith("k"), RestClient.builder(), false);
        assertThrows(IllegalArgumentException.class,
                () -> c.describe(new byte[]{1, 2}, "image/tiff"));
    }

    @Test
    void describeThrowsOn429() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));

        VisionClient c = new VisionClient(propsWith("k"), b, false);
        assertThrows(HttpClientErrorException.TooManyRequests.class,
                () -> c.describe(new byte[]{1, 2}, "image/png"));
    }
}
