package com.hivemem.oauth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuthPropertiesTest {

    private static OAuthProperties withBase(String value) {
        OAuthProperties props = new OAuthProperties();
        props.setAuthorizeRedirectBaseUrl(value);
        return props;
    }

    @Test
    void blankValueIsAllowedAndNormalisedToEmpty() {
        OAuthProperties props = withBase("   ");
        props.validateAuthorizeRedirectBaseUrl();
        assertThat(props.getAuthorizeRedirectBaseUrl()).isEmpty();
    }

    @Test
    void nullValueIsAllowedAndNormalisedToEmpty() {
        OAuthProperties props = withBase(null);
        props.validateAuthorizeRedirectBaseUrl();
        assertThat(props.getAuthorizeRedirectBaseUrl()).isEmpty();
    }

    @Test
    void validHttpsOriginIsKept() {
        OAuthProperties props = withBase("https://gui.example.com");
        props.validateAuthorizeRedirectBaseUrl();
        assertThat(props.getAuthorizeRedirectBaseUrl()).isEqualTo("https://gui.example.com");
    }

    @Test
    void trailingSlashIsStripped() {
        OAuthProperties props = withBase("https://gui.example.com/");
        props.validateAuthorizeRedirectBaseUrl();
        assertThat(props.getAuthorizeRedirectBaseUrl()).isEqualTo("https://gui.example.com");
    }

    @Test
    void plainHttpIsRejected() {
        assertThatThrownBy(() -> withBase("http://gui.example.com").validateAuthorizeRedirectBaseUrl())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authorize-redirect-base-url");
    }

    @Test
    void urlWithPathIsRejected() {
        assertThatThrownBy(() -> withBase("https://gui.example.com/oauth").validateAuthorizeRedirectBaseUrl())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authorize-redirect-base-url");
    }

    @Test
    void urlWithQueryIsRejected() {
        assertThatThrownBy(() -> withBase("https://gui.example.com?a=b").validateAuthorizeRedirectBaseUrl())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authorize-redirect-base-url");
    }

    @Test
    void valueWithoutHostIsRejected() {
        assertThatThrownBy(() -> withBase("https://").validateAuthorizeRedirectBaseUrl())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authorize-redirect-base-url");
    }

    @Test
    void malformedUriIsRejected() {
        assertThatThrownBy(() -> withBase("https://gui example.com").validateAuthorizeRedirectBaseUrl())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authorize-redirect-base-url");
    }
}
