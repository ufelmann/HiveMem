package com.hivemem.embedding;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class HttpEmbeddingClient implements EmbeddingClient {

    private final RestClient restClient;

    @Autowired
    public HttpEmbeddingClient(RestClient.Builder builder, EmbeddingProperties properties) {
        this(builder, properties, true);
    }

    HttpEmbeddingClient(RestClient.Builder builder, EmbeddingProperties properties, boolean configureRequestFactory) {
        int timeoutMillis = Math.toIntExact(properties.getTimeout().toMillis());
        if (configureRequestFactory) {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(timeoutMillis);
            requestFactory.setReadTimeout(timeoutMillis);
            builder = builder.requestFactory(requestFactory);
        }
        this.restClient = builder.baseUrl(properties.getBaseUrl().toString()).build();
    }

    @Override
    public List<Float> encodeDocument(String text) {
        return encode(text, "document");
    }

    @Override
    public List<Float> encodeQuery(String text) {
        return encode(text, "query");
    }

    private List<Float> encode(String text, String mode) {
        String jsonBody = "{\"text\":" + toJsonString(text) + ",\"mode\":\"" + mode + "\"}";
        EmbeddingResponse response = restClient.post()
                .uri("/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(jsonBody)
                .retrieve()
                .body(EmbeddingResponse.class);
        if (response == null || response.vector() == null) {
            throw new IllegalStateException("Missing embedding vector");
        }
        return List.copyOf(response.vector());
    }

    private static String toJsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    record EmbeddingResponse(List<Float> vector) {
    }
}
