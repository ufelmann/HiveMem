package com.hivemem.queen;

import com.hivemem.consumption.SeparationApplier;
import com.hivemem.contradiction.ContradictionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VistierieWebhookControllerTest {

    private QueenWebhookService service;
    private MockMvc mvc;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void up() {
        service = mock(QueenWebhookService.class);
        QueenProperties p = new QueenProperties();
        p.setEnabled(true);
        p.setWebhookToken("wt");
        p.setCompletionWebhookToken("cwt");
        p.setContradictionWebhookToken("ctok");
        ObjectProvider<SeparationApplier> noApplier = mock(ObjectProvider.class);
        when(noApplier.getIfAvailable()).thenReturn(null);
        ObjectProvider<ContradictionService> noContradictionService = mock(ObjectProvider.class);
        when(noContradictionService.getIfAvailable()).thenReturn(null);
        mvc = MockMvcBuilders.standaloneSetup(
                        new VistierieWebhookController(p, service, noApplier, noContradictionService))
                .build();
    }

    @Test
    void rejectsBadToolToken() throws Exception {
        mvc.perform(post("/vistierie/tools/find_isolated_cells")
                        .header("Authorization", "Bearer wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"run_id\":\"r1\",\"tool_name\":\"find_isolated_cells\",\"input\":{\"limit\":5}}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void findIsolatedCellsReturnsOutputEnvelope() throws Exception {
        when(service.findIsolatedCells(anyInt())).thenReturn(Map.of("cell_ids", List.of("c1", "c2")));
        mvc.perform(post("/vistierie/tools/find_isolated_cells")
                        .header("Authorization", "Bearer wt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"run_id\":\"r1\",\"tool_name\":\"find_isolated_cells\",\"input\":{\"limit\":5}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.output.cell_ids[0]").value("c1"));
    }

    @Test
    void completionIngestsProposals() throws Exception {
        when(service.ingestProposals(org.mockito.ArgumentMatchers.anyList())).thenReturn(1);
        mvc.perform(post("/vistierie/runs/done")
                        .header("Authorization", "Bearer cwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"run_id":"r1","status":"done","output":{"surveyed":1,
                                 "proposals":[{"from_cell":"a","to_cell":"b","relation":"related_to","note":"x"}]}}
                                """))
                .andExpect(status().isOk());
        verify(service).ingestProposals(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void completionIgnoresFailedRuns() throws Exception {
        mvc.perform(post("/vistierie/runs/done")
                        .header("Authorization", "Bearer cwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"run_id\":\"r1\",\"status\":\"failed\",\"output\":null}"))
                .andExpect(status().isOk());
        verify(service, org.mockito.Mockito.never())
                .ingestProposals(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void rejectsBadCompletionToken() throws Exception {
        mvc.perform(post("/vistierie/runs/done")
                        .header("Authorization", "Bearer wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"run_id\":\"r1\",\"status\":\"done\",\"output\":{}}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readCellReturnsOutputEnvelope() throws Exception {
        when(service.readCell(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Map.of("id", "c1", "content", "hello"));
        mvc.perform(post("/vistierie/tools/read_cell")
                        .header("Authorization", "Bearer wt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"run_id\":\"r1\",\"tool_name\":\"read_cell\",\"input\":{\"cell_id\":\"c1\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.output.id").value("c1"));
    }

    @Test
    void rejectsBadContradictionToken() throws Exception {
        mvc.perform(post("/vistierie/contradiction/done")
                        .header("Authorization", "Bearer wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"run_id\":\"r1\",\"status\":\"done\",\"output\":{\"verdicts\":[]}}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsMissingTokenOnContradictionDone() throws Exception {
        mvc.perform(post("/vistierie/contradiction/done")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"run_id\":\"r1\",\"status\":\"done\",\"output\":{\"verdicts\":[]}}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsBadCardinalityToken() throws Exception {
        mvc.perform(post("/vistierie/cardinality/done")
                        .header("Authorization", "Bearer wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"run_id\":\"r1\",\"status\":\"done\",\"output\":{\"verdicts\":[]}}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsMissingTokenOnCardinalityDone() throws Exception {
        mvc.perform(post("/vistierie/cardinality/done")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"run_id\":\"r1\",\"status\":\"done\",\"output\":{\"verdicts\":[]}}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aMissingBeanAnswersTwoHundredNotFiveOhThreeOnContradictionDone() throws Exception {
        mvc.perform(post("/vistierie/contradiction/done")
                        .header("Authorization", "Bearer ctok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"run_id\":\"r1\",\"status\":\"done\",\"output\":{\"verdicts\":[]}}"))
                .andExpect(status().isOk());
    }

    @Test
    void aMissingBeanAnswersTwoHundredNotFiveOhThreeOnCardinalityDone() throws Exception {
        mvc.perform(post("/vistierie/cardinality/done")
                        .header("Authorization", "Bearer ctok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"run_id\":\"r1\",\"status\":\"done\",\"output\":{\"verdicts\":[]}}"))
                .andExpect(status().isOk());
    }

    /**
     * A missing {@code run_id} must be rejected as a bad request — but only on the enabled path
     * (a valid token, a present {@code ContradictionService} bean). Using the shared {@code mvc}
     * (whose {@code ObjectProvider} always returns {@code null}) would pin the missing-bean 200
     * branch instead, since that check runs before the null-{@code run_id} check — these two tests
     * build their own MockMvc with a present bean so the 400 they assert is the real one.
     */
    @SuppressWarnings("unchecked")
    @Test
    void rejectsMissingRunIdOnContradictionDone() throws Exception {
        QueenProperties p = new QueenProperties();
        p.setEnabled(true);
        p.setContradictionWebhookToken("ctok");
        ObjectProvider<SeparationApplier> noApplier = mock(ObjectProvider.class);
        ObjectProvider<ContradictionService> withContradictionService = mock(ObjectProvider.class);
        when(withContradictionService.getIfAvailable()).thenReturn(mock(ContradictionService.class));
        MockMvc enabledMvc = MockMvcBuilders.standaloneSetup(
                        new VistierieWebhookController(p, service, noApplier, withContradictionService))
                .build();

        enabledMvc.perform(post("/vistierie/contradiction/done")
                        .header("Authorization", "Bearer ctok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"done\"}"))
                .andExpect(status().isBadRequest());
    }

    @SuppressWarnings("unchecked")
    @Test
    void rejectsMissingRunIdOnCardinalityDone() throws Exception {
        QueenProperties p = new QueenProperties();
        p.setEnabled(true);
        p.setContradictionWebhookToken("ctok");
        ObjectProvider<SeparationApplier> noApplier = mock(ObjectProvider.class);
        ObjectProvider<ContradictionService> withContradictionService = mock(ObjectProvider.class);
        when(withContradictionService.getIfAvailable()).thenReturn(mock(ContradictionService.class));
        MockMvc enabledMvc = MockMvcBuilders.standaloneSetup(
                        new VistierieWebhookController(p, service, noApplier, withContradictionService))
                .build();

        enabledMvc.perform(post("/vistierie/cardinality/done")
                        .header("Authorization", "Bearer ctok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"done\"}"))
                .andExpect(status().isBadRequest());
    }
}
