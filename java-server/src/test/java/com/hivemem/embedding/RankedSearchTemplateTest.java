package com.hivemem.embedding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RankedSearchTemplateTest {

    @Test
    void rendersTemplateWithGivenDimension() {
        String sql = RankedSearchTemplate.render(384);

        assertThat(sql).contains("vector(384)");
        assertThat(sql).doesNotContain("{{DIM}}");
        assertThat(sql).doesNotContain("vector(1024)");
        assertThat(sql).contains("CREATE OR REPLACE FUNCTION ranked_search");
    }

    @Test
    void rendersAllPlaceholderOccurrences() {
        String sql = RankedSearchTemplate.render(768);

        long count = sql.lines().filter(l -> l.contains("vector(768)")).count();
        assertThat(count).isGreaterThanOrEqualTo(2);
    }

    @Test
    void rejectsNonPositiveDimension() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> RankedSearchTemplate.render(0));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> RankedSearchTemplate.render(-1));
    }
}
