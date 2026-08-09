package com.hivemem.queen;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.cells.CellReadRepository;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.search.CellSearchRepository;
import com.hivemem.search.CellSearchRepository.RankedRow;
import com.hivemem.write.WriteToolService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QueenWebhookServiceTest {

    private final CellReadRepository cells = mock(CellReadRepository.class);
    private final CellSearchRepository search = mock(CellSearchRepository.class);
    private final EmbeddingClient embedding = mock(EmbeddingClient.class);
    private final WriteToolService writes = mock(WriteToolService.class);
    private final QueenRepository repo = mock(QueenRepository.class);
    private final VistierieRunsClient runsClient = mock(VistierieRunsClient.class);

    private QueenWebhookService service() {
        QueenProperties p = new QueenProperties();
        p.setIsolatedBatchLimit(20);
        return new QueenWebhookService(p, repo, cells, search, embedding, writes, runsClient);
    }

    @Test
    void findIsolatedCellsCapsAtBatchLimit() {
        QueenProperties p = new QueenProperties();
        p.setIsolatedBatchLimit(2);
        QueenWebhookService svc = new QueenWebhookService(p, repo, cells, search, embedding, writes, runsClient);
        svc.findIsolatedCells(1000);
        verify(repo).findIsolatedCellIds(2);
    }

    @Test
    void searchExcludesSelf() {
        UUID self = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        when(cells.findCell(eq(self), any())).thenReturn(Optional.of(Map.of("content", "abc")));
        when(embedding.encodeQuery("abc")).thenReturn(List.of(0.1f, 0.2f));
        when(search.rankedSearch(any(), anyString(), isNull(), isNull(), isNull(), anyInt(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), any(), any()))
                .thenReturn(List.of(
                        new RankedRow(self, "self", "selfsum", "work", "facts", "t", List.of(), 3,
                                List.of(), null,
                                OffsetDateTime.now(), OffsetDateTime.now(), null,
                                1.0, 0, 0, 0, 0, 0, 1.0, null, null, null),
                        new RankedRow(other, "o", "othersum", "work", "facts", "t", List.of(), 3,
                                List.of(), null,
                                OffsetDateTime.now(), OffsetDateTime.now(), null,
                                0.8, 0, 0, 0, 0, 0, 0.8, null, null, null)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates =
                (List<Map<String, Object>>) service().searchSimilarCells(self.toString(), 5).get("candidates");
        assertThat(candidates).extracting(c -> c.get("cell_id")).containsExactly(other.toString());
    }

    @Test
    void ingestWritesPendingAndSkipsDuplicatesAndBadRelations() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        UUID dupTo = UUID.randomUUID();
        when(repo.tunnelExists(from, to, "related_to")).thenReturn(false);
        when(repo.tunnelExists(from, dupTo, "related_to")).thenReturn(true);

        int written = service().ingestProposals(List.of(
                Map.of("from_cell", from.toString(), "to_cell", to.toString(),
                        "relation", "related_to", "note", "linked"),
                Map.of("from_cell", from.toString(), "to_cell", dupTo.toString(),
                        "relation", "related_to"),
                Map.of("from_cell", from.toString(), "to_cell", to.toString(),
                        "relation", "bogus")));

        assertThat(written).isEqualTo(1);
        verify(writes).addTunnel(
                argThatIsQueenAgent(), eq(from), eq(to), eq("related_to"), eq("linked"), eq("pending"));
        verify(writes, never()).addTunnel(any(), eq(from), eq(dupTo), anyString(), any(), anyString());
    }

    @Test
    void ingestToleratesNonMapItems() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        List<Map<String, Object>> bad = (List) java.util.List.of("not-a-map");
        int written = service().ingestProposals(bad);
        org.assertj.core.api.Assertions.assertThat(written).isEqualTo(0);
    }

    @Test
    void recoverProposalsFromChildRunsIngestsOnlyDoneBeesOfThisParent() {
        UUID from1 = UUID.randomUUID();
        UUID from2 = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID to1 = UUID.randomUUID();
        UUID to2 = UUID.randomUUID();
        when(repo.tunnelExists(any(), any(), anyString())).thenReturn(false);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode body = mapper.readTree("""
                [
                  {"run_id":"b1","agent_name":"isolated-cell-bee","parent_run_id":"q1","status":"done",
                   "output":{"cell_id":"%s","proposals":[{"to_cell":"%s","relation":"related_to","note":"n1"}]}},
                  {"run_id":"b2","agent_name":"isolated-cell-bee","parent_run_id":"q1","status":"done",
                   "output":{"cell_id":"%s","proposals":[{"to_cell":"%s","relation":"builds_on"}]}},
                  {"run_id":"b3","agent_name":"isolated-cell-bee","parent_run_id":"q1","status":"failed",
                   "output":null},
                  {"run_id":"b4","agent_name":"isolated-cell-bee","parent_run_id":"other-queen-run","status":"done",
                   "output":{"cell_id":"%s","proposals":[]}},
                  {"run_id":"q1","agent_name":"queen","parent_run_id":null,"status":"failed","output":null}
                ]
                """.formatted(from1, to1, from2, to2, other));
        when(runsClient.listRunsTenantScoped(eq(100), any())).thenReturn(body);

        int written = service().recoverProposalsFromChildRuns("q1", "2026-08-09T03:00:00Z");

        assertThat(written).isEqualTo(2);
        verify(writes).addTunnel(argThatIsQueenAgent(), eq(from1), eq(to1), eq("related_to"), eq("n1"), eq("pending"));
        verify(writes).addTunnel(argThatIsQueenAgent(), eq(from2), eq(to2), eq("builds_on"), isNull(), eq("pending"));
        verify(writes, org.mockito.Mockito.times(2))
                .addTunnel(any(), any(), any(), anyString(), any(), anyString());
        verify(runsClient).listRunsTenantScoped(100, java.time.Instant.parse("2026-08-09T03:00:00Z"));
    }

    /** A missing/unparseable started_at falls back to an unbounded (still limit-capped) query. */
    @Test
    void recoverProposalsFromChildRunsFallsBackToNullFromWhenStartedAtMissingOrUnparseable() {
        ObjectMapper mapper = new ObjectMapper();
        when(runsClient.listRunsTenantScoped(eq(100), any())).thenReturn(mapper.readTree("[]"));

        service().recoverProposalsFromChildRuns("q1", null);
        verify(runsClient).listRunsTenantScoped(100, null);

        service().recoverProposalsFromChildRuns("q1", "not-a-valid-instant");
        verify(runsClient, org.mockito.Mockito.times(2)).listRunsTenantScoped(100, null);
    }

    @Test
    void recoverProposalsFromChildRunsToleratesTransportFailure() {
        when(runsClient.listRunsTenantScoped(anyInt(), any()))
                .thenThrow(new VistierieUnavailableException("down", new RuntimeException("boom")));
        int written = service().recoverProposalsFromChildRuns("q1", null);
        assertThat(written).isEqualTo(0);
    }

    @Test
    void recoverProposalsFromChildRunsHandlesEmptyList() {
        ObjectMapper mapper = new ObjectMapper();
        when(runsClient.listRunsTenantScoped(eq(100), any())).thenReturn(mapper.readTree("[]"));
        int written = service().recoverProposalsFromChildRuns("q1", null);
        assertThat(written).isEqualTo(0);
        verifyNoInteractions(writes);
    }

    private static AuthPrincipal argThatIsQueenAgent() {
        return org.mockito.ArgumentMatchers.argThat(
                pr -> pr != null && "queen".equals(pr.name()) && pr.role() == AuthRole.AGENT);
    }
}
