package com.hivemem.embedding;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(EmbeddingProperties.class)
    static class EnableEmbeddingPropertiesConfig {
    }

    @Test
    void maxRetriesAndRetryBackoffMsBindFromConfiguration() {
        new ApplicationContextRunner()
                .withUserConfiguration(EnableEmbeddingPropertiesConfig.class)
                .withPropertyValues(
                        "hivemem.embedding.max-retries=0",
                        "hivemem.embedding.retry-backoff-ms=1500")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .getBean(EmbeddingProperties.class)
                        .satisfies(props -> {
                            assertThat(props.getMaxRetries()).isEqualTo(0);
                            assertThat(props.getRetryBackoffMs()).isEqualTo(1500L);
                        }));
    }

    @Test
    void maxRetriesAndRetryBackoffMsDefaultWhenUnset() {
        new ApplicationContextRunner()
                .withUserConfiguration(EnableEmbeddingPropertiesConfig.class)
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .getBean(EmbeddingProperties.class)
                        .satisfies(props -> {
                            assertThat(props.getMaxRetries()).isEqualTo(3);
                            assertThat(props.getRetryBackoffMs()).isEqualTo(500L);
                        }));
    }
}
