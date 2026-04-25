package com.hivemem.search;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = SearchWeightsProperties.class)
@TestPropertySource(properties = {
        "hivemem.search.weights.semantic=0.30",
        "hivemem.search.weights.keyword=0.15",
        "hivemem.search.weights.recency=0.15",
        "hivemem.search.weights.importance=0.15",
        "hivemem.search.weights.popularity=0.15",
        "hivemem.search.weights.graph-proximity=0.10",
})
class SearchWeightsPropertiesTest {

    @Autowired SearchWeightsProperties props;

    @Test
    void weightsBindFromYaml() {
        SearchWeights w = props.toSearchWeights();
        assertThat(w.semantic()).isEqualTo(0.30);
        assertThat(w.keyword()).isEqualTo(0.15);
        assertThat(w.recency()).isEqualTo(0.15);
        assertThat(w.importance()).isEqualTo(0.15);
        assertThat(w.popularity()).isEqualTo(0.15);
        assertThat(w.graphProximity()).isEqualTo(0.10);
    }

    @Test
    void defaultsAreSelfConsistent() {
        SearchWeights w = SearchWeights.defaults();
        assertThat(w.semantic() + w.keyword() + w.recency()
                + w.importance() + w.popularity() + w.graphProximity())
                .isEqualTo(1.00);
    }
}
