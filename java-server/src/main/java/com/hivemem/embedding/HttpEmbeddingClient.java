package com.hivemem.embedding;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.UnknownContentTypeException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@Component
public class HttpEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(HttpEmbeddingClient.class);

    private final RestClient restClient;
    private volatile int expectedDimension = -1;
    private final int maxRetries;
    private final long retryBackoffMs;
    /** Bounded content-hash → vector cache: identical texts (repeated queries, dedupe checks,
     *  hook lookups, sync replay) skip the HTTP hop. Keyed by mode + SHA-256 of the text. */
    private final Cache<String, List<Float>> vectorCache;
    /** Cached /info result serving {@link #dimension()} without an HTTP hop per caller. */
    private volatile EmbeddingInfo cachedInfo;
    /** Deliberately NOT cleared by invalidateCaches(): this is a backend property, not a
     *  per-migration one, and encodeForCell runs on the request path where getInfo() has
     *  neither retry nor EmbeddingUnavailableException wrapping. */
    private volatile int cachedMaxChars = -1;

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
        this.maxRetries = Math.max(0, properties.getMaxRetries());
        this.retryBackoffMs = Math.max(0, properties.getRetryBackoffMs());
        this.vectorCache = Caffeine.newBuilder()
                .maximumSize(properties.getCacheMaxEntries())
                .expireAfterWrite(properties.getCacheTtl())
                .build();
    }

    @Override
    public EmbeddingInfo getInfo() {
        InfoResponse response = restClient.get()
                .uri("/info")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(InfoResponse.class);
        if (response == null || response.model() == null) {
            throw new IllegalStateException("Embedding service /info returned invalid response");
        }
        if (response.model().isBlank()) {
            throw new IllegalStateException("Embedding service returned no model name");
        }
        if (response.dimension() <= 0) {
            throw new IllegalStateException("Embedding service returned dimension " + response.dimension());
        }
        int maxChars = response.maxChars() != null ? response.maxChars() : 0;
        if (maxChars <= 0) {
            throw new IllegalStateException(
                    "Embedding service returned no usable max_chars — the sidecar is older than this "
                  + "server. Deploy both images together.");
        }
        this.expectedDimension = response.dimension();
        this.cachedMaxChars = maxChars;
        EmbeddingInfo info = new EmbeddingInfo(response.model(), response.dimension());
        this.cachedInfo = info;
        return info;
    }

    /** The embedding dimension from the cached model info; only the first call pays an HTTP hop. */
    @Override
    public int dimension() {
        EmbeddingInfo info = cachedInfo;
        return (info != null ? info : getInfo()).dimension();
    }

    @Override
    public void invalidateCaches() {
        cachedInfo = null;
        vectorCache.invalidateAll();
    }

    /** Deliberately NOT cleared by invalidateCaches(): this is a backend property, not a
     *  per-migration one, and encodeForCell runs on the request path where getInfo() has
     *  neither retry nor EmbeddingUnavailableException wrapping. */
    @Override
    public int maxChars() {
        if (cachedMaxChars > 0) return cachedMaxChars;
        try {
            return getInfo() != null ? cachedMaxChars : CONTENT_EMBED_MAX_CHARS;
        } catch (Exception e) {
            log.warn("max_chars refresh failed, using {}: {}", CONTENT_EMBED_MAX_CHARS, e.getMessage());
            return CONTENT_EMBED_MAX_CHARS;
        }
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
        String cacheKey = mode + ':' + contentHash(text);
        List<Float> cached = vectorCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }
        List<Float> vector = encodeRemote(text, mode);
        vectorCache.put(cacheKey, vector);
        return vector;
    }

    private List<Float> encodeRemote(String text, String mode) {
        String jsonBody = "{\"text\":" + toJsonString(text) + ",\"mode\":\"" + mode + "\"}";
        int attempt = 0;
        while (true) {
            try {
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
                if (expectedDimension > 0 && response.vector().size() != expectedDimension) {
                    throw new IllegalStateException("Embedding dimension mismatch: expected "
                            + expectedDimension + " but got " + response.vector().size());
                }
                return List.copyOf(response.vector());
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                throw e; // 4xx deterministic — no retry
            } catch (org.springframework.web.client.HttpServerErrorException
                     | org.springframework.web.client.ResourceAccessException e) {
                if (attempt++ >= maxRetries) {
                    throw new EmbeddingUnavailableException(
                            "Embedding service unavailable after " + attempt + " attempt(s)", e);
                }
                log.debug("Embedding call retry {} for mode={}: {}", attempt, mode, e.toString());
                sleepBackoff(attempt);
            } catch (RestClientException e) {
                // e.g. UnknownContentTypeException when the service replies application/octet-stream:
                // previously fell through uncaught and unlogged. Log it, then rethrow unchanged.
                String contentType = (e instanceof UnknownContentTypeException uce)
                        ? String.valueOf(uce.getContentType()) : "unknown";
                log.warn("Embedding call failed for mode={} textLen={}: {} (content-type={})",
                        mode, text.length(), e.getClass().getSimpleName(), contentType);
                throw e;
            }
        }
    }

    private void sleepBackoff(int attempt) {
        long delay = retryBackoffMs * (1L << Math.min(attempt - 1, 30)); // cap shift so large retry counts can't wrap
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new EmbeddingUnavailableException("Interrupted during embedding retry backoff", ie);
        }
    }

    /** SHA-256 hex of the text — bounds cache-key size regardless of content length. */
    static String contentHash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // guaranteed by the JDK spec
        }
    }

    static String toJsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** maxChars is boxed (not int) so a missing/absent max_chars in the JSON — the exact
     *  version-skew case this endpoint must reject — binds to null instead of making Jackson
     *  throw before the {@code getInfo()} validation below gets a chance to produce a clear
     *  "deploy both images together" message. */
    record InfoResponse(String model, int dimension, @JsonProperty("max_chars") Integer maxChars) {
    }

    record EmbeddingResponse(List<Float> vector, String model, Integer dimension) {
    }
}
