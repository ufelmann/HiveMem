package com.hivemem.hooks;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = HookPropertiesTest.TestConfig.class)
@TestPropertySource(properties = {
        "hivemem.hooks.enabled=false",
        "hivemem.hooks.relevance-threshold=0.42",
        "hivemem.hooks.max-cells=7",
        "hivemem.hooks.dedup-window-turns=11",
})
class HookPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(HookProperties.class)
    static class TestConfig {
    }

    @Autowired HookProperties props;

    @Test
    void bindsAllFieldsFromYaml() {
        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getRelevanceThreshold()).isEqualTo(0.42);
        assertThat(props.getMaxCells()).isEqualTo(7);
        assertThat(props.getDedupWindowTurns()).isEqualTo(11);
    }
}
