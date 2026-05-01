package com.hivemem.attachment;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KrokiClientTest {

    private AttachmentProperties propsWith(String url) {
        AttachmentProperties p = new AttachmentProperties();
        p.setKrokiUrl(url);
        p.setKrokiTimeoutSeconds(5);
        return p;
    }

    @Test
    void supportsKnownMimeTypes() {
        AttachmentProperties p = propsWith("http://kroki:8000");
        KrokiClient c = new KrokiClient(p, RestClient.builder(), false);
        assertTrue(c.supports("text/x-mermaid"));
        assertTrue(c.supports("text/x-plantuml"));
        assertTrue(c.supports("text/vnd.graphviz"));
        assertTrue(c.supports("text/x-d2"));
        assertFalse(c.supports("text/plain"));
        assertFalse(c.supports(null));
    }

    @Test
    void notEnabledWhenUrlBlank() {
        KrokiClient c = new KrokiClient(propsWith(""), RestClient.builder(), false);
        assertFalse(c.isEnabled());
    }

    @Test
    void rendersMermaidPng() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        byte[] png = new byte[]{(byte)0x89, 'P', 'N', 'G'};
        server.expect(requestTo("http://kroki:8000/mermaid/png"))
                .andRespond(withSuccess(png, MediaType.IMAGE_PNG));

        KrokiClient c = new KrokiClient(propsWith("http://kroki:8000"), b, false);
        Optional<byte[]> out = c.render("text/x-mermaid", "graph TD; A-->B");
        assertTrue(out.isPresent());
        assertArrayEquals(png, out.get());
    }

    @Test
    void returnsEmptyOnHttp4xx() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        server.expect(requestTo("http://kroki:8000/mermaid/png"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST));

        KrokiClient c = new KrokiClient(propsWith("http://kroki:8000"), b, false);
        Optional<byte[]> out = c.render("text/x-mermaid", "invalid syntax");
        assertTrue(out.isEmpty());
    }

    @Test
    void returnsEmptyOnUnsupportedMime() {
        KrokiClient c = new KrokiClient(propsWith("http://kroki:8000"), RestClient.builder(), false);
        assertTrue(c.render("text/plain", "x").isEmpty());
    }

    @Test
    void returnsEmptyOnBlankBody() {
        KrokiClient c = new KrokiClient(propsWith("http://kroki:8000"), RestClient.builder(), false);
        assertTrue(c.render("text/x-mermaid", "").isEmpty());
        assertTrue(c.render("text/x-mermaid", null).isEmpty());
    }
}
