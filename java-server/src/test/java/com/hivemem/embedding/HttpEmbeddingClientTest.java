package com.hivemem.embedding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(OutputCaptureExtension.class)
class HttpEmbeddingClientTest {

    /** FIX 5: toJsonString must escape control chars U+0000-U+001F so the request body is valid JSON.
     *  toJsonString is package-private so it can be called directly from this test. */
    @Test
    void toJsonStringEscapesControlChars() {
        // String with NUL (\u0000), BEL (\u0007), and a normal newline
        String input = "a" + '\u0000' + "b" + '\u0007' + "c\nd";
        String result = HttpEmbeddingClient.toJsonString(input);
        // Must be a valid quoted JSON string
        assertThat(result).startsWith("\"").endsWith("\"");
        // Control chars must be escaped, not present raw
        assertThat(result).contains("\\u0000");
        assertThat(result).contains("\\u0007");
        assertThat(result).contains("\\n");
        // Must not contain any raw control character (0x00-0x1F)
        for (char c : result.toCharArray()) {
            assertThat((int) c)
                    .as("raw control char 0x%02x found in JSON string", (int) c)
                    .isGreaterThanOrEqualTo(0x20);
        }
    }

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

    @Test
    void logsAndPropagatesOctetStreamResponse(CapturedOutput output) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpEmbeddingClient client = new HttpEmbeddingClient(
                builder,
                new EmbeddingProperties(java.net.URI.create("https://embeddings.local"), java.time.Duration.ofSeconds(2)),
                false);

        server.expect(requestTo("https://embeddings.local/embeddings"))
                .andExpect(method(POST))
                .andRespond(withSuccess(" ", MediaType.APPLICATION_OCTET_STREAM));

        assertThatThrownBy(() -> client.encodeDocument("drawer content"))
                .isInstanceOf(RestClientException.class);
        assertThat(output).contains("Embedding call failed").contains("application/octet-stream");
        server.verify();
    }

    private record ClientAndServer(HttpEmbeddingClient client, MockRestServiceServer server) {
    }

    private ClientAndServer newClient() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpEmbeddingClient client = new HttpEmbeddingClient(
                builder,
                new EmbeddingProperties(java.net.URI.create("https://embeddings.local"), java.time.Duration.ofSeconds(2)),
                false);
        return new ClientAndServer(client, server);
    }

    private ClientAndServer stubInfo(String json) {
        ClientAndServer cs = newClient();
        cs.server().expect(requestTo("https://embeddings.local/info"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        return cs;
    }

    @Test
    void bindsMaxCharsFromSnakeCaseJson() {
        ClientAndServer cs = stubInfo(
                InfoStub.json("m/mrl1024/t2560/contentfirst", 1024, 8000));
        assertThat(cs.client().maxChars()).isEqualTo(8000);
        cs.server().verify();
    }

    @Test
    void rejectsInfoWithoutMaxChars_asVersionSkew() {
        ClientAndServer cs = stubInfo("{\"model\":\"m\",\"dimension\":1024}");
        // Assert the MESSAGE, not just the class: getInfo() throws IllegalStateException
        // for several unrelated reasons, so a class-only assertion passes for the wrong one.
        assertThatThrownBy(cs.client()::getInfo)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max_chars");
        cs.server().verify();
    }

    /** Pins that invalidateCaches() deliberately does NOT clear cachedMaxChars: after a warm
     *  getInfo(), maxChars() must keep returning the cached value from the fast path — without
     *  making a second /info call — even once the rest of the cache (cachedInfo) is cleared. */
    @Test
    void maxCharsSurvivesInvalidateCaches_becauseItIsNotAPerMigrationCache() {
        ClientAndServer cs = newClient();
        cs.server().expect(requestTo("https://embeddings.local/info"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(InfoStub.json("m", 1024, 8000), MediaType.APPLICATION_JSON));

        cs.client().getInfo();
        cs.client().invalidateCaches();

        assertThat(cs.client().maxChars()).isEqualTo(8000);
        cs.server().verify(); // only the one /info call above — no second HTTP hop
    }

    /** Cold-cache sibling of the test above: no prior getInfo(), so maxChars() must itself call
     *  /info. When that call fails (sidecar restarting right after a migration's
     *  invalidateCaches(), before any getInfo() ever warmed the cache), maxChars() must fall
     *  back to CONTENT_EMBED_MAX_CHARS rather than propagate — this is the exact path
     *  encodeForCell relies on never throwing. */
    @Test
    void maxCharsFallsBackToDefault_whenColdCacheAndInfoCallFails() {
        ClientAndServer cs = newClient();
        cs.server().expect(requestTo("https://embeddings.local/info"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withServerError());

        assertThat(cs.client().maxChars()).isEqualTo(EmbeddingClient.CONTENT_EMBED_MAX_CHARS);
        cs.server().verify();
    }
}
