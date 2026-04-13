package com.hivemem.embedding;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpEmbeddingClientTest {

    @Test
    void mapsDocumentEmbeddingResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpEmbeddingClient client = new HttpEmbeddingClient(
                builder,
                new EmbeddingProperties(java.net.URI.create("https://embeddings.local"), java.time.Duration.ofSeconds(2)),
                false
        );
        server.expect(requestTo("https://embeddings.local/embeddings"))
                .andExpect(method(POST))
                .andExpect(content().json("""
                        {"text":"drawer content","mode":"document"}
                        """))
                .andRespond(withSuccess("""
                        {"vector":[0.1,0.2,0.3]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.encodeDocument("drawer content")).containsExactly(0.1f, 0.2f, 0.3f);
        server.verify();
    }
}
