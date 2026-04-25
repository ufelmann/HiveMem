package com.hivemem.hooks;

import com.hivemem.search.CellSearchRepository.RankedRow;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContextFormatterTest {

    private final ContextFormatter f = new ContextFormatter();

    @Test
    void formatsSingleCellAsCompactXmlWithTurn() {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        RankedRow row = new RankedRow(id, "full",
                "Phase 3 plan: SDK wrapper, 4 weeks",
                "engineering", "facts", "events", List.of("plan"), 1,
                OffsetDateTime.now(), null, null,
                0.8, 0.0, 0.0, 0.0, 0.0, 0.0, 0.85);

        String out = f.format(List.of(row), 23);

        assertThat(out).startsWith("<hivemem_context turn=\"23\">");
        assertThat(out).contains("(id: 11111111-1111-1111-1111-111111111111)");
        assertThat(out).contains("Phase 3 plan: SDK wrapper, 4 weeks");
        assertThat(out).endsWith("</hivemem_context>");
    }

    @Test
    void emptyListReturnsEmptyString() {
        assertThat(f.format(List.of(), 1)).isEmpty();
    }

    @Test
    void nullListReturnsEmptyString() {
        assertThat(f.format(null, 1)).isEmpty();
    }

    @Test
    void multipleCellsRenderAsBulletList() {
        RankedRow a = sampleRow(UUID.randomUUID(), "first summary");
        RankedRow b = sampleRow(UUID.randomUUID(), "second summary");

        String out = f.format(List.of(a, b), 7);

        assertThat(out).contains("- first summary");
        assertThat(out).contains("- second summary");
    }

    @Test
    void fallsBackToContentWhenSummaryIsBlank() {
        UUID id = UUID.randomUUID();
        RankedRow row = new RankedRow(id, "this is the full content",
                "", // blank summary
                "r", "s", "t", List.of(), 3,
                OffsetDateTime.now(), null, null,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.5);

        String out = f.format(List.of(row), 1);

        assertThat(out).contains("this is the full content");
    }

    private RankedRow sampleRow(UUID id, String summary) {
        return new RankedRow(id, "x", summary, "r", "s", "t",
                List.of(), 3, OffsetDateTime.now(), null, null,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.5);
    }
}
