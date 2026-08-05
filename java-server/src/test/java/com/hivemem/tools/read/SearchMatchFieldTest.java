package com.hivemem.tools.read;

import com.hivemem.attachment.AttachmentRepository;
import com.hivemem.cells.CellReadRepository;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.kg.KgEntityRepository;
import com.hivemem.search.CellSearchRepository;
import com.hivemem.search.CellSelectorRepository;
import com.hivemem.search.ConfidenceThresholds;
import com.hivemem.search.DataQualityRepository;
import com.hivemem.search.DocumentListRepository;
import com.hivemem.search.FacetRepository;
import com.hivemem.search.KgSearchRepository;
import com.hivemem.search.MediaListRepository;
import com.hivemem.search.SearchWeightsProperties;
import com.hivemem.write.AdminToolService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Design §3.7: the MCP {@code search} response carries a {@code match} object
 * (page_from, page_to, excerpt) only when a chunk supplied the score; otherwise the field
 * is absent entirely — never present-but-null. Within {@code match}, page numbers are
 * themselves omitted when the chunk carried no page marker (roughly three quarters of
 * chunked cells, per the design doc's measurement).
 */
class SearchMatchFieldTest {

    private final EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
    private final CellSearchRepository cellSearchRepository = mock(CellSearchRepository.class);

    private ReadToolService service() {
        return new ReadToolService(
                mock(CellReadRepository.class),
                mock(KgSearchRepository.class),
                cellSearchRepository,
                embeddingClient,
                mock(AdminToolService.class),
                mock(SearchWeightsProperties.class),
                new ConfidenceThresholds(0.20),
                mock(AttachmentRepository.class),
                mock(FacetRepository.class),
                mock(DocumentListRepository.class),
                mock(MediaListRepository.class),
                mock(CellSelectorRepository.class),
                mock(DataQualityRepository.class),
                mock(KgEntityRepository.class)
        );
    }

    private static CellSearchRepository.RankedRow rowWithMatch(
            Integer pageFrom, Integer pageTo, String excerpt) {
        return new CellSearchRepository.RankedRow(
                UUID.randomUUID(), "content", "summary", "realm", "facts", "topic",
                List.of(), 3, List.of(), null, OffsetDateTime.now(), OffsetDateTime.now(), null,
                0.7, 0.5, 0.3, 0.6, 0.0, 0.0, 0.9, pageFrom, pageTo, excerpt);
    }

    private List<Map<String, Object>> searchOnce(CellSearchRepository.RankedRow row) throws Exception {
        when(embeddingClient.encodeQuery(anyString())).thenReturn(List.of(0.1f));
        when(cellSearchRepository.rankedSearch(
                any(), anyString(), any(), any(), any(), anyInt(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                any(), any(), any()))
                .thenReturn(List.of(row));

        return service().search(
                "Bausparsumme Zusammenlegung", 10, null, null, null,
                CellFieldSelection.forSearch(null),
                0.30, 0.15, 0.15, 0.15, 0.15, 0.10,
                null, null, null, true);
    }

    @Test
    void chunkHitCarriesMatchWithPageRange() throws Exception {
        CellSearchRepository.RankedRow row =
                rowWithMatch(12, 13, "...Zusammenlegung und Teilung der Bausparsumme...");

        List<Map<String, Object>> results = searchOnce(row);

        assertThat(results).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> match = (Map<String, Object>) results.get(0).get("match");
        assertThat(match).isNotNull();
        assertThat(match.get("page_from")).isEqualTo(12);
        assertThat(match.get("page_to")).isEqualTo(13);
        assertThat(match.get("excerpt")).isEqualTo("...Zusammenlegung und Teilung der Bausparsumme...");
    }

    @Test
    void chunkHitWithoutPageMarkerOmitsPageFields() throws Exception {
        CellSearchRepository.RankedRow row = rowWithMatch(null, null, "unpaginiertes Fundstueck");

        List<Map<String, Object>> results = searchOnce(row);

        @SuppressWarnings("unchecked")
        Map<String, Object> match = (Map<String, Object>) results.get(0).get("match");
        assertThat(match).isNotNull();
        assertThat(match).doesNotContainKeys("page_from", "page_to");
        assertThat(match.get("excerpt")).isEqualTo("unpaginiertes Fundstueck");
    }

    @Test
    void cellVectorHitHasNoMatchKeyAtAll() throws Exception {
        CellSearchRepository.RankedRow row = rowWithMatch(null, null, null);

        List<Map<String, Object>> results = searchOnce(row);

        assertThat(results.get(0)).doesNotContainKey("match");
    }
}
