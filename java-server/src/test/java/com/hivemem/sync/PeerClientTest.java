package com.hivemem.sync;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class PeerClientTest {

    private HttpServer server;
    private String baseUrl;
    private PeerClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        baseUrl = "http://localhost:" + port;
        client = new PeerClient(RestClient.builder(), objectMapper);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void fetchOpsCallsCorrectUrlWithAuth() throws Exception {
        ObjectNode responseBody = objectMapper.createObjectNode();
        responseBody.putArray("ops");
        responseBody.put("max_seq", 0);
        byte[] responseBytes = objectMapper.writeValueAsBytes(responseBody);

        server.createContext("/sync/ops", exchange -> {
            assertThat(exchange.getRequestURI().getQuery()).contains("since=5");
            assertThat(exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                    .isEqualTo("Bearer test-token");
            exchange.getResponseHeaders().set("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            exchange.sendResponseHeaders(200, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
            exchange.close();
        });
        server.start();

        List<OpDto> ops = client.fetchOps(baseUrl, 5L, "test-token");
        assertThat(ops).isEmpty();
    }

    @Test
    void pushOpsSilentlyIgnoresErrors() {
        // peer URL that won't respond — must not throw
        OpDto op = new OpDto(1L, UUID.randomUUID(), "add_cell",
                objectMapper.createObjectNode(), OffsetDateTime.now());
        assertThatNoException().isThrownBy(
                () -> client.pushOps("http://localhost:1", UUID.randomUUID(), List.of(op), "tok"));
    }
}
