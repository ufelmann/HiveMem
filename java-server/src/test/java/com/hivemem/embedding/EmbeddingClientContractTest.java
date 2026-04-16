package com.hivemem.embedding;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EmbeddingClientContractTest {

    @Test
    void encodeQueryUsesConfiguredEmbeddingsEndpointAndRequestShape() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpEmbeddingClient client = new HttpEmbeddingClient(
                builder,
                new EmbeddingProperties(URI.create("https://embeddings.local/api"), Duration.ofSeconds(2)),
                false
        );

        server.expect(requestTo("https://embeddings.local/api/embeddings"))
                .andExpect(method(POST))
                .andExpect(content().json("""
                        {"text":"search prompt","mode":"query"}
                        """))
                .andRespond(withSuccess("""
                        {"vector":[0.5,0.25]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.encodeQuery("search prompt")).containsExactly(0.5f, 0.25f);
        server.verify();
    }

    @Test
    void encodeDocumentMapsDenseVectorWithoutChangingDimensions() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpEmbeddingClient client = new HttpEmbeddingClient(
                builder,
                new EmbeddingProperties(URI.create("https://embeddings.local"), Duration.ofSeconds(2)),
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

    @Test
    void encodeDocumentToleratesAdditionalResponseFields() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpEmbeddingClient client = new HttpEmbeddingClient(
                builder,
                new EmbeddingProperties(URI.create("https://embeddings.local"), Duration.ofSeconds(2)),
                false
        );

        server.expect(requestTo("https://embeddings.local/embeddings"))
                .andExpect(method(POST))
                .andRespond(withSuccess("""
                        {
                          "vector":[0.9,0.8,0.7],
                          "sparse":{"12":0.4},
                          "model":"bge-m3"
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.encodeDocument("drawer content")).containsExactly(0.9f, 0.8f, 0.7f);
        server.verify();
    }
}
