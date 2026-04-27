package com.hivemem.hooks;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.search.CellSearchRepository;
import com.hivemem.search.CellSearchRepository.RankedRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class HookContextServiceTest {

    private CellSearchRepository repo;
    private EmbeddingClient embed;
    private HookContextService svc;
    private HookProperties props;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(CellSearchRepository.class);
        embed = Mockito.mock(EmbeddingClient.class);
        when(embed.encodeQuery(anyString())).thenReturn(List.of(0f));
        props = new HookProperties();
        svc = new HookContextService(repo, embed, new SkipHeuristics(),
                new SessionInjectionCache(), new ContextFormatter(), props);
    }

    @Test
    void skipsTrivialPrompt() {
        String out = svc.contextFor(new HookContextRequest("UserPromptSubmit", "ok", "s1", null));
        assertThat(out).isEmpty();
        Mockito.verifyNoInteractions(repo);
    }

    @Test
    void emptyWhenAllResultsBelowThreshold() {
        when(repo.rankedSearch(any(), anyString(), any(), any(), any(), anyInt(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(weakRow()));
        String out = svc.contextFor(new HookContextRequest(
                "UserPromptSubmit", "What was the plan for project X phase 3?", "s1", null));
        assertThat(out).isEmpty();
    }

    @Test
    void formatsResultsAboveThreshold() {
        when(repo.rankedSearch(any(), anyString(), any(), any(), any(), anyInt(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(strongRow()));
        String out = svc.contextFor(new HookContextRequest(
                "UserPromptSubmit", "What was the plan for project X phase 3?", "s1", null));
        assertThat(out).contains("<hivemem_context");
    }

    @Test
    void dedupSuppressesRepeatedInjection() {
        RankedRow row = strongRow();
        when(repo.rankedSearch(any(), anyString(), any(), any(), any(), anyInt(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(row));
        var req = new HookContextRequest(
                "UserPromptSubmit", "What was the plan for project X phase 3?", "s1", null);
        svc.contextFor(req);
        String second = svc.contextFor(req);
        assertThat(second).isEmpty();
    }

    @Test
    void disabledReturnsEmpty() {
        props.setEnabled(false);
        String out = svc.contextFor(new HookContextRequest(
                "UserPromptSubmit", "What was the plan for project X phase 3?", "s1", null));
        assertThat(out).isEmpty();
        Mockito.verifyNoInteractions(repo);
    }

    @Test
    void searchExceptionReturnsEmpty() {
        when(repo.rankedSearch(any(), anyString(), any(), any(), any(), anyInt(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenThrow(new RuntimeException("db down"));
        String out = svc.contextFor(new HookContextRequest(
                "UserPromptSubmit", "What was the plan for project X phase 3?", "s1", null));
        assertThat(out).isEmpty();
    }

    @Test
    void semanticFloorFiltersKeywordOnlyMatch() {
        // high total score but near-zero semantic → should be filtered by minSemanticScore
        RankedRow keywordOnly = new RankedRow(UUID.randomUUID(), "x", "keyword match", "r", "s", "t",
                List.of(), 3, OffsetDateTime.now(), null, null,
                0.1, 0.9, 0.0, 0.0, 0.0, 0.0, 0.70);
        when(repo.rankedSearch(any(), anyString(), any(), any(), any(), anyInt(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(keywordOnly));
        String out = svc.contextFor(new HookContextRequest(
                "UserPromptSubmit", "What was the plan for project X phase 3?", "s1", null));
        assertThat(out).isEmpty();
    }

    @Test
    void usesHookPrecisionWeightsNotSearchWeights() {
        when(repo.rankedSearch(any(), anyString(), any(), any(), any(), anyInt(),
                eq(0.70), eq(0.10), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(strongRow()));

        String out = svc.contextFor(new HookContextRequest(
                "UserPromptSubmit", "What was the plan for project X phase 3?", "s1", null));

        assertThat(out).contains("<hivemem_context");
    }

    @Test
    void cwdProjectMatchingCellSortedFirst() {
        UUID projectId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        RankedRow projectCell = new RankedRow(projectId, "x", "hivemem summary", "tech", "s", "hivemem",
                List.of("hivemem"), 1, OffsetDateTime.now(), null, null,
                0.8, 0.0, 0.0, 0.0, 0.0, 0.0, 0.80);
        RankedRow otherCell = new RankedRow(otherId, "x", "other summary", "tech", "s", "ansible",
                List.of("ansible"), 1, OffsetDateTime.now(), null, null,
                0.85, 0.0, 0.0, 0.0, 0.0, 0.0, 0.85);
        when(repo.rankedSearch(any(), anyString(), any(), any(), any(), anyInt(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(otherCell, projectCell));

        String out = svc.contextFor(new HookContextRequest(
                "UserPromptSubmit", "What was the plan for project X phase 3?", "s2", "/root/hivemem"),
                0.5, 5);

        assertThat(out).isNotEmpty();
        int projectPos = out.indexOf("hivemem summary");
        int otherPos = out.indexOf("other summary");
        assertThat(projectPos).isLessThan(otherPos);
    }

    private RankedRow weakRow() {
        return new RankedRow(UUID.randomUUID(), "x", "x", "r", "s", "t",
                List.of(), 3, OffsetDateTime.now(), null, null,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.10);
    }

    private RankedRow strongRow() {
        return new RankedRow(UUID.randomUUID(), "x", "Phase 3 plan", "r", "s", "t",
                List.of(), 1, OffsetDateTime.now(), null, null,
                0.9, 0.0, 0.0, 0.0, 0.0, 0.0, 0.90);
    }
}
