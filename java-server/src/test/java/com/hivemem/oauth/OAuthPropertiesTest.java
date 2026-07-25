package com.hivemem.oauth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuthPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(OAuthProperties.class)
    static class EnableOAuthPropertiesConfig {
    }

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
    void validOriginIsTrimmedAndNormalised() {
        OAuthProperties props = withBase("  https://gui.example.com//  ");
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
    void opaqueUriWithoutHostIsRejected() {
        // "https:foo" parses successfully as an opaque URI (no authority, no throw): scheme=https,
        // host=null, rawPath=null, rawQuery=null, rawFragment=null. It therefore reaches the
        // originOnly expression and is rejected specifically by the host==null clause, not by the
        // URISyntaxException catch block and not by the path/query/fragment clauses (all of which
        // are satisfied). Confirmed via a standalone java.net.URI probe on JDK 26.
        assertThatThrownBy(() -> withBase("https:foo").validateAuthorizeRedirectBaseUrl())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authorize-redirect-base-url");
    }

    @Test
    void malformedUriIsRejected() {
        assertThatThrownBy(() -> withBase("https://gui example.com").validateAuthorizeRedirectBaseUrl())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authorize-redirect-base-url");
    }

    @Test
    void urlWithFragmentIsRejected() {
        assertThatThrownBy(() -> withBase("https://gui.example.com#frag").validateAuthorizeRedirectBaseUrl())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authorize-redirect-base-url");
    }

    @Test
    void validValueBindsFromTheEnvironmentAndIsNormalisedDuringStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(EnableOAuthPropertiesConfig.class)
                .withPropertyValues("hivemem.oauth.authorize-redirect-base-url=https://gui.example.com/")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .getBean(OAuthProperties.class)
                        .extracting(OAuthProperties::getAuthorizeRedirectBaseUrl)
                        .isEqualTo("https://gui.example.com"));
    }

    @Test
    void malformedValueAbortsStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(EnableOAuthPropertiesConfig.class)
                .withPropertyValues("hivemem.oauth.authorize-redirect-base-url=https://gui.example.com/oauth")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("authorize-redirect-base-url"));
    }
}
