package com.hivemem.attachment;

import com.hivemem.testsupport.MockVistierieServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class VisionClientTest {

    MockVistierieServer mock;
    VisionClient client;

    @BeforeEach
    void up() {
        mock = new MockVistierieServer();
        mock.start();
        var http = RestClient.builder()
                .baseUrl(mock.baseUrl())
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
        client = new VisionClient(http, "vtok", 5_242_880L);
    }

    @AfterEach
    void down() { mock.stop(); }

    @Test
    void describeUsesVistierie() {
        mock.stubVision("a square box");
        var result = client.describe(new byte[]{1, 2, 3}, "image/png");
        assertThat(result.description()).isEqualTo("a square box");
        assertThat(result.inputTokens()).isEqualTo(50);
        assertThat(result.outputTokens()).isEqualTo(4);
    }

    @Test
    void transcribeUsesVistierie() {
        mock.stubVision("some transcribed text");
        var result = client.transcribe(new byte[]{1, 2, 3}, "image/jpeg");
        assertThat(result.description()).isEqualTo("some transcribed text");
    }

    @Test
    void describeImageParsesJsonSubType() {
        mock.stubVision("{\\\"sub_type\\\":\\\"whiteboard_photo\\\",\\\"content\\\":\\\"Roadmap: Q1 -> Q2\\\"}");
        var result = client.describeImage(new byte[]{1, 2, 3}, "image/png");
        assertThat(result.subType()).isEqualTo("whiteboard_photo");
        assertThat(result.content()).isEqualTo("Roadmap: Q1 -> Q2");
    }

    @Test
    void describeRejectsOversizeImage() {
        byte[] tooBig = new byte[6 * 1024 * 1024];
        assertThrows(VisionClient.OversizeImageException.class,
                () -> client.describe(tooBig, "image/png"));
    }

    @Test
    void describeRejectsUnsupportedMime() {
        assertThrows(IllegalArgumentException.class,
                () -> client.describe(new byte[]{1, 2}, "image/tiff"));
    }

    @Test
    void isEnabledWhenTokenSet() {
        assertThat(client.isEnabled()).isTrue();
    }

    @Test
    void isDisabledWhenTokenBlank() {
        var http = RestClient.builder()
                .baseUrl(mock.baseUrl())
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
        VisionClient c = new VisionClient(http, "", 5_242_880L);
        assertThat(c.isEnabled()).isFalse();
    }
}
