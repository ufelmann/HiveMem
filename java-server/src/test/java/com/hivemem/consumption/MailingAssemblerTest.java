package com.hivemem.consumption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClientException;

class MailingAssemblerTest {

    private static PageMetadataExtractor.PageMetadata meta(int page, String sender, String date) {
        return new PageMetadataExtractor.PageMetadata(page, sender, date, null, "letter", null,
                "a page", false, false);
    }

    @Test
    void parsesMailingsIntoOrderedDocGroups() {
        CompleteClient cc = mock(CompleteClient.class);
        when(cc.complete(eq("documents"), anyString())).thenReturn("""
                [{"mailing":"en","description":"SYNTHETIC ENERGY order 01.01.2000","confidence":0.9,
                  "pages":[16,15,14,12]},
                 {"mailing":"wa","description":"SYNTHETIC WASTE invoice","confidence":0.8,"pages":[17]}]""");
        List<DocGroup> groups = new MailingAssembler(cc)
                .assemble("documents", List.of(meta(12, "SYNTHETIC ENERGY", null),
                        meta(14, "SYNTHETIC ENERGY", "01.01.2000"),
                        meta(15, "SYNTHETIC ENERGY", "01.01.2000"),
                        meta(16, "SYNTHETIC ENERGY", "01.01.2000"),
                        meta(17, "SYNTHETIC WASTE", "02.02.2001")));
        assertEquals(2, groups.size());
        assertEquals("en", groups.get(0).id);
        assertEquals("SYNTHETIC ENERGY order 01.01.2000", groups.get(0).descriptor);
        assertEquals(List.of(16, 15, 14, 12), groups.get(0).pages); // reading order preserved
        assertEquals(0.9, groups.get(0).minConfidence, 1e-9);
        assertEquals(List.of(17), groups.get(1).pages);
    }

    @Test
    void rendersOneRowPerPageWithPythonStyleNulls() {
        CompleteClient cc = mock(CompleteClient.class);
        when(cc.complete(anyString(), anyString()))
                .thenReturn("[{\"mailing\":\"m\",\"description\":\"d\",\"confidence\":1.0,\"pages\":[1]}]");
        new MailingAssembler(cc).assemble("documents", List.of(
                new PageMetadataExtractor.PageMetadata(1, "SYNTHETIC INSURER", null, null, "letter",
                        "Vertrags-Nr SYNTHETIC-0002", "Confirmation letter.", false, false)));
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(cc).complete(anyString(), prompt.capture());
        // matches the validated row format: nulls rendered as None, strings single-quoted
        assertTrue(prompt.getValue().contains(
                "- page 1: sender='SYNTHETIC INSURER', date=None, printed_page_label=None, blank=false, "
                        + "reference='Vertrags-Nr SYNTHETIC-0002', content='letter' - 'Confirmation letter.'"));
    }

    @Test
    void multiLineSummaryRendersOnOneRow() {
        CompleteClient cc = mock(CompleteClient.class);
        when(cc.complete(anyString(), anyString()))
                .thenReturn("[{\"mailing\":\"m\",\"description\":\"d\",\"confidence\":1.0,\"pages\":[1]}]");
        new MailingAssembler(cc).assemble("documents", List.of(
                new PageMetadataExtractor.PageMetadata(1, "SYNTHETIC INSURER", null, null, "letter",
                        null, "line one\nline two\r\nline three", false, false)));
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(cc).complete(anyString(), prompt.capture());
        String rowsSection = prompt.getValue();
        assertTrue(rowsSection.contains("'line one line two  line three'"));
        // exactly one row for this page: no embedded newline broke it into multiple lines
        assertEquals(1, rowsSection.split("- page 1:", -1).length - 1);
    }

    @Test
    void normalizesTheModelsGroupsBeforeReturningThem() {
        CompleteClient cc = mock(CompleteClient.class);
        when(cc.complete(eq("documents"), anyString())).thenReturn("""
                [{"mailing":"fa","description":"Finanzamt Bescheid","confidence":0.9,"pages":[1]},
                 {"mailing":"fa2","description":"Finanzamt Bescheid","confidence":0.5,
                  "pages":[2]}]""");
        List<DocGroup> groups = new MailingAssembler(cc).assemble("documents", List.of(
                new PageMetadataExtractor.PageMetadata(1, "Finanzamt Musterstadt", "05.09.2025",
                        null, "Bescheid", "12/345/67890", "page one", false, false),
                new PageMetadataExtractor.PageMetadata(2, "Finanzamt Musterstadt", "05.09.2025",
                        null, "Bescheid", "12/345/6789O", "page two", false, false)));
        // same sender + issue date: the prompt forbids two such mailings, the normalizer enforces it
        assertEquals(1, groups.size());
        assertEquals(List.of(1, 2), groups.get(0).pages);
        assertEquals(0.5, groups.get(0).minConfidence, 1e-9);
    }

    @Test
    void garbageOutputThrows() {
        CompleteClient cc = mock(CompleteClient.class);
        when(cc.complete(anyString(), anyString())).thenReturn("sorry, I cannot help with that");
        MailingAssembler assembler = new MailingAssembler(cc);
        List<PageMetadataExtractor.PageMetadata> pages = List.of(meta(1, "X", null));
        assertThrows(IllegalStateException.class, () -> assembler.assemble("documents", pages));
    }

    @Test
    void transientFailureIsRetriedOnce() {
        CompleteClient cc = mock(CompleteClient.class);
        when(cc.complete(eq("documents"), anyString()))
                .thenThrow(new RestClientException("boom"))
                .thenReturn("[{\"mailing\":\"m\",\"description\":\"d\",\"confidence\":1.0,\"pages\":[1]}]");
        List<DocGroup> groups = new MailingAssembler(cc)
                .assemble("documents", List.of(meta(1, "X", null)));
        assertEquals(1, groups.size());
        verify(cc, times(2)).complete(eq("documents"), anyString());
    }

    @Test
    void persistentFailureThrowsAfterTwoAttempts() {
        CompleteClient cc = mock(CompleteClient.class);
        when(cc.complete(eq("documents"), anyString()))
                .thenThrow(new RestClientException("boom"));
        MailingAssembler assembler = new MailingAssembler(cc);
        List<PageMetadataExtractor.PageMetadata> pages = List.of(meta(1, "X", null));
        assertThrows(RestClientException.class, () -> assembler.assemble("documents", pages));
        verify(cc, times(2)).complete(eq("documents"), anyString());
    }

    private static DocGroup g(String id, double confidence, int... pages) {
        DocGroup d = new DocGroup(id, id + " descriptor");
        for (int p : pages) d.pages.add(p);
        d.minConfidence = confidence;
        return d;
    }

    @Test
    void threeIdenticalDrawsReproduceThatPartition() {
        List<DocGroup> draw = List.of(g("a", 0.9, 1, 2, 3), g("b", 0.8, 4, 5));
        List<DocGroup> out = MailingAssembler.consensus(List.of(draw, draw, draw),
                List.of(1, 2, 3, 4, 5));
        assertEquals(2, out.size());
        assertEquals(List.of(1, 2, 3), out.get(0).pages);
        assertEquals(List.of(4, 5), out.get(1).pages);
    }

    @Test
    void aMajorityToSplitBeatsTheSingleDrawThatMerged() {
        List<DocGroup> merged = List.of(g("m", 0.9, 1, 2, 3, 4));
        List<DocGroup> split = List.of(g("s1", 0.9, 1, 2), g("s2", 0.9, 3, 4));
        List<DocGroup> out = MailingAssembler.consensus(List.of(merged, split, split),
                List.of(1, 2, 3, 4));
        assertEquals(2, out.size());
        assertEquals(List.of(1, 2), out.get(0).pages);
        assertEquals(List.of(3, 4), out.get(1).pages);
    }

    @Test
    void aMajorityToMergeBeatsTheSingleDrawThatSplit() {
        List<DocGroup> merged = List.of(g("m", 0.9, 1, 2, 3, 4));
        List<DocGroup> split = List.of(g("s1", 0.9, 1, 2), g("s2", 0.9, 3, 4));
        List<DocGroup> out = MailingAssembler.consensus(List.of(merged, merged, split),
                List.of(1, 2, 3, 4));
        assertEquals(1, out.size());
        assertEquals(List.of(1, 2, 3, 4), out.get(0).pages);
    }

    @Test
    void aPairShortOfTheThresholdDoesNotMergeEvenWhenItIsTheMostCommonReading() {
        // Two draws pair 1~2, two pair 2~3. threshold = 4/2+1 = 3, so neither reaches it and all
        // three pages stay apart. This is the conservative direction on purpose.
        List<DocGroup> d1 = List.of(g("x", 0.9, 1, 2), g("y", 0.9, 3));
        List<DocGroup> d2 = List.of(g("x", 0.9, 2, 3), g("y", 0.9, 1));
        List<DocGroup> d3 = List.of(g("x", 0.9, 1, 2), g("y", 0.9, 3));
        List<DocGroup> d4 = List.of(g("x", 0.9, 2, 3), g("y", 0.9, 1));
        List<DocGroup> out = MailingAssembler.consensus(List.of(d1, d2, d3, d4), List.of(1, 2, 3));
        assertEquals(3, out.size());
    }

    @Test
    void unionFindClosesAChainOfMajorityPairs() {
        // 1~2 and 2~3 each reach the threshold; page 1 and page 3 must end up together.
        List<DocGroup> d1 = List.of(g("x", 0.9, 1, 2, 3));
        List<DocGroup> d2 = List.of(g("x", 0.9, 1, 2, 3));
        List<DocGroup> d3 = List.of(g("x", 0.9, 1), g("y", 0.9, 2), g("z", 0.9, 3));
        List<DocGroup> out = MailingAssembler.consensus(List.of(d1, d2, d3), List.of(1, 2, 3));
        assertEquals(1, out.size());
        assertEquals(List.of(1, 2, 3), out.get(0).pages);
    }

    @Test
    void twoDrawsRequireUnanimityToMerge() {
        // threshold = 2/2+1 = 2, so a single draw's merge must NOT win.
        List<DocGroup> merged = List.of(g("m", 0.9, 1, 2));
        List<DocGroup> split = List.of(g("s1", 0.9, 1), g("s2", 0.9, 2));
        List<DocGroup> out = MailingAssembler.consensus(List.of(merged, split), List.of(1, 2));
        assertEquals(2, out.size());
    }

    @Test
    void aPageMissingFromEveryDrawBecomesItsOwnGroup() {
        List<DocGroup> draw = List.of(g("a", 0.9, 1, 2));
        List<DocGroup> out = MailingAssembler.consensus(List.of(draw, draw, draw),
                List.of(1, 2, 3));
        assertEquals(2, out.size());
        assertEquals(List.of(1, 2), out.get(0).pages);
        assertEquals(List.of(3), out.get(1).pages);
    }

    @Test
    void aPageMissingFromOneDrawStillMergesOnTheMajority() {
        List<DocGroup> full = List.of(g("a", 0.9, 1, 2));
        List<DocGroup> partial = List.of(g("a", 0.9, 1));
        List<DocGroup> out = MailingAssembler.consensus(List.of(full, full, partial),
                List.of(1, 2));
        assertEquals(1, out.size());
        assertEquals(List.of(1, 2), out.get(0).pages);
    }

    @Test
    void groupsAreOrderedByTheirLowestPage() {
        // Group order is still by lowest page; the page order WITHIN a group is a separate concern
        // (see consensusKeepsTheBestMatchingDrawsReadingOrderWithinAGroup) — here the "early" group
        // was declared as (3, 1), so that draw order — not ascending — is what survives.
        List<DocGroup> draw = List.of(g("late", 0.9, 9, 7), g("early", 0.9, 3, 1));
        List<DocGroup> out = MailingAssembler.consensus(List.of(draw, draw, draw),
                List.of(1, 3, 7, 9));
        assertEquals(List.of(3, 1), out.get(0).pages);
        assertEquals(List.of(9, 7), out.get(1).pages);
    }

    @Test
    void consensusKeepsTheBestMatchingDrawsReadingOrderWithinAGroup() {
        // The prompt asks for reading order (letter, continuation pages, enclosures, blanks last),
        // and that order survives into the produced sub-PDF — the vote must not silently re-sort it.
        List<DocGroup> draw = List.of(g("a", 0.9, 4, 2, 3));
        List<DocGroup> out = MailingAssembler.consensus(List.of(draw, draw, draw), List.of(2, 3, 4));
        assertEquals(1, out.size());
        assertEquals(List.of(4, 2, 3), out.get(0).pages);
    }

    @Test
    void confidenceEqualsBaseWhenAllDrawsAgree() {
        List<DocGroup> draw = List.of(g("a", 0.9, 1, 2));
        List<DocGroup> out = MailingAssembler.consensus(List.of(draw, draw, draw), List.of(1, 2));
        assertEquals(1, out.size());
        assertEquals(0.9, out.get(0).minConfidence, 1e-9);
    }

    @Test
    void aLowConfidenceDrawThatMergesEverythingDoesNotDragDownAnUnrelatedComponent() {
        // The low-confidence draw fully overlaps EVERY component (it merged all 6 pages), so it
        // ties on overlap with each split group — but the split group (size 2) is tighter than the
        // merged one (size 6), so findBest's size tie-break keeps it as the best match regardless of
        // draw order, and 0.2 never becomes the base. The merged draw is listed FIRST here
        // specifically so earliest-draw-wins alone would pick it (wrongly) — only the size
        // tie-break saves this.
        List<DocGroup> merged = List.of(g("m", 0.2, 1, 2, 3, 4, 5, 6));
        List<DocGroup> split = List.of(g("s1", 0.9, 1, 2), g("s2", 0.9, 3, 4), g("s3", 0.9, 5, 6));
        List<DocGroup> out = MailingAssembler.consensus(List.of(merged, split, split),
                List.of(1, 2, 3, 4, 5, 6));
        assertEquals(3, out.size());
        assertEquals(0.9, out.get(0).minConfidence, 1e-9);
        assertEquals(0.9, out.get(1).minConfidence, 1e-9);
        assertEquals(0.9, out.get(2).minConfidence, 1e-9);
    }

    @Test
    void partialAgreementLowersConfidenceBelowTheBase() {
        // threshold = 3/2+1 = 2; two of three draws agree on {1,2}, so it merges, but the vote was
        // not unanimous — the confidence must reflect that, not just copy the base 0.9.
        List<DocGroup> merged = List.of(g("m", 0.9, 1, 2));
        List<DocGroup> split = List.of(g("s1", 0.9, 1), g("s2", 0.9, 2));
        List<DocGroup> out = MailingAssembler.consensus(List.of(merged, merged, split), List.of(1, 2));
        assertEquals(1, out.size());
        assertEquals(0.6, out.get(0).minConfidence, 1e-9); // 0.9 base * (2 of 3 draws agreed)
    }

    @Test
    void tiedOverlapPrefersTheTighterGroupOverASuperset() {
        // "big" (listed FIRST, confidence 0.3) covers all four pages; "small" (listed second,
        // confidence 0.9) covers exactly the {1,2} component. Both tie on overlap=2 with that
        // component, so only the size tie-break (small=2 < big=4) can pick the right one. Under the
        // old earliest-draw-wins tie-break this test would fail: "big" comes first, ties on overlap,
        // and the old code never looked past the tie to prefer the tighter group — so the {1,2}
        // component would incorrectly inherit "big"'s 0.3 instead of "small"'s 0.9.
        List<DocGroup> drawA = List.of(g("big", 0.3, 1, 2, 3, 4));
        List<DocGroup> drawB = List.of(g("small", 0.9, 1, 2), g("rest", 0.9, 3, 4));
        List<DocGroup> out = MailingAssembler.consensus(List.of(drawA, drawB), List.of(1, 2, 3, 4));
        assertEquals(2, out.size());
        assertEquals(List.of(1, 2), out.get(0).pages);
        assertEquals(0.9, out.get(0).minConfidence, 1e-9); // "small"'s confidence, not "big"'s 0.3
    }

    @Test
    void consensusNeverThrowsOnDegenerateInput() {
        assertEquals(0, MailingAssembler.consensus(List.of(), List.of()).size());
        assertEquals(0, MailingAssembler.consensus(List.of(List.of()), List.of()).size());
        List<DocGroup> out = MailingAssembler.consensus(List.of(List.of()), List.of(1, 2));
        assertEquals(2, out.size()); // no draw grouped anything -> two singletons
    }

    @Test
    void aSingleDrawBehavesExactlyAsBefore() {
        CompleteClient cc = mock(CompleteClient.class);
        when(cc.complete(anyString(), anyString())).thenReturn(
                "[{\"mailing\":\"m\",\"description\":\"d\",\"confidence\":0.9,\"pages\":[1,2]}]");
        List<DocGroup> out = new MailingAssembler(cc, 1).assemble("documents",
                List.of(meta(1, "SYNTHETIC ENERGY", "01.01.2000"),
                        meta(2, "SYNTHETIC ENERGY", "01.01.2000")));
        verify(cc, times(1)).complete(anyString(), anyString());
        assertEquals(1, out.size());
        assertEquals("m", out.get(0).id);
    }

    @Test
    void threeDrawsCallTheModelThreeTimesAndVote() {
        CompleteClient cc = mock(CompleteClient.class);
        when(cc.complete(anyString(), anyString()))
                .thenReturn("[{\"mailing\":\"a\",\"description\":\"d\",\"confidence\":0.9,\"pages\":[1,2]}]")
                .thenReturn("[{\"mailing\":\"b\",\"description\":\"d\",\"confidence\":0.9,\"pages\":[1]},"
                        + "{\"mailing\":\"c\",\"description\":\"d\",\"confidence\":0.9,\"pages\":[2]}]")
                .thenReturn("[{\"mailing\":\"d\",\"description\":\"d\",\"confidence\":0.9,\"pages\":[1,2]}]");
        List<DocGroup> out = new MailingAssembler(cc, 3).assemble("documents",
                List.of(meta(1, "SYNTHETIC ENERGY", "01.01.2000"),
                        meta(2, "SYNTHETIC ENERGY", "01.01.2000")));
        verify(cc, times(3)).complete(anyString(), anyString());
        assertEquals(1, out.size()); // 2 of 3 draws merged
        assertEquals(List.of(1, 2), out.get(0).pages);
    }

    @Test
    void aFailedDrawIsSkippedAndTheRestStillVote() {
        CompleteClient cc = mock(CompleteClient.class);
        when(cc.complete(anyString(), anyString()))
                .thenReturn("[{\"mailing\":\"a\",\"description\":\"d\",\"confidence\":0.9,\"pages\":[1,2]}]")
                .thenThrow(new RestClientException("boom"))   // draw 2, attempt 1
                .thenThrow(new RestClientException("boom"))   // draw 2, attempt 2
                .thenReturn("[{\"mailing\":\"c\",\"description\":\"d\",\"confidence\":0.9,\"pages\":[1,2]}]");
        List<DocGroup> out = new MailingAssembler(cc, 3).assemble("documents",
                List.of(meta(1, "SYNTHETIC ENERGY", "01.01.2000"),
                        meta(2, "SYNTHETIC ENERGY", "01.01.2000")));
        assertEquals(1, out.size());
        assertEquals(List.of(1, 2), out.get(0).pages);
    }

    @Test
    void everyDrawFailingStillThrowsSoTheOrchestratorCanDegrade() {
        CompleteClient cc = mock(CompleteClient.class);
        when(cc.complete(anyString(), anyString())).thenThrow(new RestClientException("boom"));
        assertThrows(RuntimeException.class, () -> new MailingAssembler(cc, 3)
                .assemble("documents", List.of(meta(1, "SYNTHETIC ENERGY", "01.01.2000"))));
    }

    @Test
    void promptAllowsSameSenderSameDateWhenReferencesDiffer() {
        // The old blanket ban is what merged an insurer's annual mailing into one document.
        assertThat(MailingAssembler.PROMPT)
                .doesNotContain("It is FORBIDDEN to output two mailings with the same sender and "
                        + "the same letter date");
        // The exception must be stated, and it must be tied to the reference number.
        assertThat(MailingAssembler.PROMPT).contains("clearly different reference numbers");
        // The enclosure rule must survive: a form ID is not a reference for this purpose.
        assertThat(MailingAssembler.PROMPT).contains("An enclosure ALWAYS joins the mailing");
    }

    @Test
    void prefersTheToolInputOverText() throws Exception {
        CompleteClient cc = mock(CompleteClient.class);
        when(cc.completeWithTool(eq("documents"), anyString(), eq("submit_mailings"),
                anyString(), anyMap()))
                .thenReturn(new tools.jackson.databind.ObjectMapper().readTree("""
                        {"mailings":[
                          {"mailing":"a","description":"SYNTHETIC INSURER letter 01.01.2000",
                           "confidence":0.9,"pages":[1,2]},
                          {"mailing":"b","description":"SYNTHETIC INSURER letter 02.02.2001",
                           "confidence":0.8,"pages":[3]}]}"""));

        List<DocGroup> groups = new MailingAssembler(cc)
                .assemble("documents", List.of(meta(1, "SYNTHETIC INSURER", "01.01.2000"),
                        meta(2, "SYNTHETIC INSURER", "01.01.2000"),
                        meta(3, "SYNTHETIC INSURER", "02.02.2001")));

        assertEquals(2, groups.size());
        assertEquals("a", groups.get(0).id);
        assertEquals(List.of(1, 2), groups.get(0).pages);
        assertEquals(List.of(3), groups.get(1).pages);
        verify(cc, never()).complete(anyString(), anyString());
    }

    @Test
    void callsCompleteWithToolUsingTheToolSchemaAndFallsBackToTextWhenNoToolInput() {
        CompleteClient cc = mock(CompleteClient.class);
        when(cc.completeWithTool(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(null);
        when(cc.complete(anyString(), anyString())).thenReturn(
                "[{\"mailing\":\"m\",\"description\":\"d\",\"confidence\":1.0,\"pages\":[1]}]");

        List<DocGroup> groups = new MailingAssembler(cc)
                .assemble("documents", List.of(meta(1, "SYNTHETIC INSURER", "01.01.2000")));

        assertEquals(1, groups.size());
        assertEquals(List.of(1), groups.get(0).pages);
        verify(cc).completeWithTool(anyString(), anyString(), eq("submit_mailings"), anyString(),
                anyMap());
        ArgumentCaptor<String> textCallPrompt = ArgumentCaptor.forClass(String.class);
        verify(cc).complete(anyString(), textCallPrompt.capture());
        // The text call must carry the JSON-worded fallback prompt, not the tool-worded one — a
        // model told to call a tool that was never attached to this call, and forbidden from
        // writing text, cannot answer it.
        assertThat(textCallPrompt.getValue()).contains("STRICT JSON");
        assertThat(textCallPrompt.getValue()).doesNotContain("submit_mailings");
    }

    @Test
    void textPromptKeepsGroupingRulesButUsesTheJsonInstructionForTheFallback() {
        // The fallback carries no tool, so it must never say "call the submit_mailings tool" or
        // forbid text output — that combination is unanswerable for a call with no tools attached.
        assertThat(MailingAssembler.TEXT_PROMPT).doesNotContain("submit_mailings");
        assertThat(MailingAssembler.TEXT_PROMPT).contains("STRICT JSON");
        assertThat(MailingAssembler.TEXT_PROMPT).contains(
                "\"pages\":[<global page numbers in reading order: letter first, then its");
        // Same measured wording as the tool route, byte-for-byte.
        assertThat(MailingAssembler.TEXT_PROMPT).contains(
                "consecutive page pairs form one physical sheet");
        assertThat(MailingAssembler.TEXT_PROMPT).contains(
                "Two mailings with the same sender AND the same letter date are allowed ONLY when they");
        assertThat(MailingAssembler.TEXT_PROMPT).contains(
                "Every page must appear exactly once — re-check your output against the page list "
                        + "before\n  answering.");
    }

    @Test
    void toolCallThrowingFallsBackToTextWithinTheSameAttemptInsteadOfRetryingTheToolCall() {
        // If completeWithTool throws (gateway 400/5xx/timeout), the SAME attempt must still try the
        // text route rather than burning the attempt on a retry of the tool call — otherwise, with
        // the tool half rolled back or absent, every draw would fail where the old code succeeded.
        CompleteClient cc = mock(CompleteClient.class);
        when(cc.completeWithTool(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenThrow(new RestClientException("gateway 400"));
        when(cc.complete(anyString(), anyString())).thenReturn(
                "[{\"mailing\":\"m\",\"description\":\"d\",\"confidence\":1.0,\"pages\":[1]}]");

        List<DocGroup> groups = new MailingAssembler(cc)
                .assemble("documents", List.of(meta(1, "SYNTHETIC INSURER", "01.01.2000")));

        assertEquals(1, groups.size());
        // One attempt was enough: the tool call was tried once, failed, and text was tried in the
        // SAME attempt and succeeded — no second attempt was needed.
        verify(cc, times(1)).completeWithTool(anyString(), anyString(), anyString(), anyString(),
                anyMap());
        verify(cc, times(1)).complete(anyString(), anyString());
    }

    @Test
    void aNestedArrayReturnedAsAJsonStringFallsBackToTheTextRoute() throws Exception {
        // Real defect (2026-08-15): the gateway announces tool schemas but does not enforce them,
        // so a model can answer with "mailings" as a JSON STRING instead of an array. The assembler
        // must detect the wrong shape and use the text route instead of accepting it.
        CompleteClient cc = mock(CompleteClient.class);
        when(cc.completeWithTool(eq("documents"), anyString(), eq("submit_mailings"), anyString(),
                anyMap()))
                .thenReturn(new tools.jackson.databind.ObjectMapper().readTree(
                        "{\"mailings\":\"[{\\\"mailing\\\":\\\"m1\\\",\\\"pages\\\":[1,2]}]\"}"));
        when(cc.complete(eq("documents"), anyString())).thenReturn(
                "[{\"mailing\":\"text-route\",\"description\":\"d\",\"confidence\":0.7,"
                        + "\"pages\":[1,2]}]");

        List<DocGroup> groups = new MailingAssembler(cc)
                .assemble("documents", List.of(meta(1, "SYNTHETIC INSURER", "01.01.2000"),
                        meta(2, "SYNTHETIC INSURER", "01.01.2000")));

        assertEquals(1, groups.size());
        assertEquals("text-route", groups.get(0).id);
        assertEquals(List.of(1, 2), groups.get(0).pages);
        verify(cc).complete(anyString(), anyString());
    }

    @Test
    void promptTellsTheModelToCallTheToolAndKeepsTheGroupingRules() {
        assertThat(MailingAssembler.PROMPT).contains("submit_mailings");
        assertThat(MailingAssembler.PROMPT).doesNotContain("STRICT JSON");
        // The rules that were measured into their wording must survive verbatim.
        assertThat(MailingAssembler.PROMPT).contains(
                "consecutive page pairs form one physical sheet");
        assertThat(MailingAssembler.PROMPT).contains(
                "Two mailings with the same sender AND the same letter date are allowed ONLY when they");
        assertThat(MailingAssembler.PROMPT).contains("Every page exactly once across all mailings.");
    }

    private static List<PageMetadataExtractor.PageMetadata> pages(int n) {
        List<PageMetadataExtractor.PageMetadata> out = new java.util.ArrayList<>();
        for (int i = 1; i <= n; i++) out.add(meta(i, "SYNTHETIC INSURER", "01.01.2000"));
        return out;
    }

    private static String oneGroupJson(int from, int to) {
        StringBuilder p = new StringBuilder();
        for (int i = from; i <= to; i++) p.append(i).append(i == to ? "" : ",");
        return "[{\"mailing\":\"all\",\"description\":\"d\",\"confidence\":0.9,\"pages\":["
                + p + "]}]";
    }

    @Test
    void rejectsADegenerateSingleDraw() {
        CompleteClient cc = mock(CompleteClient.class);
        when(cc.completeWithTool(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(null);
        when(cc.complete(anyString(), anyString())).thenReturn(oneGroupJson(1, 39));

        MailingAssembler a = new MailingAssembler(cc, 1);
        assertThrows(IllegalStateException.class, () -> a.assemble("documents", pages(41)));
    }

    @Test
    void acceptsAOneGroupDrawBelowThePageFloor() {
        CompleteClient cc = mock(CompleteClient.class);
        when(cc.completeWithTool(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(null);
        when(cc.complete(anyString(), anyString())).thenReturn(oneGroupJson(1, 12));

        List<DocGroup> groups = new MailingAssembler(cc, 1)
                .assemble("documents", pages(12));
        assertEquals(1, groups.size());
    }

    @Test
    void doesNotGuardTheVotedPath() {
        CompleteClient cc = mock(CompleteClient.class);
        when(cc.completeWithTool(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(null);
        when(cc.complete(anyString(), anyString())).thenReturn(oneGroupJson(1, 41));

        // Three agreeing draws: a genuine 41-page single document must survive the vote.
        List<DocGroup> groups = new MailingAssembler(cc, 3)
                .assemble("documents", pages(41));
        assertEquals(1, groups.size());
    }

    @Test
    void isDegenerateThresholds() {
        DocGroup big = new DocGroup("g", "d");
        for (int i = 1; i <= 39; i++) big.pages.add(i);
        DocGroup rest = new DocGroup("h", "d");
        rest.pages.add(40);
        rest.pages.add(41);
        assertTrue(MailingAssembler.isDegenerate(List.of(big, rest), 41));   // 39/41 = 95%
        assertFalse(MailingAssembler.isDegenerate(List.of(big, rest), 20));  // batch too small

        // Real boundary at a 21-page batch (threshold = 0.90 * 21 = 18.9): a 19-page group clears
        // it and must be degenerate, an 18-page group falls short and must not be.
        DocGroup nineteen = new DocGroup("i", "d");
        for (int i = 1; i <= 19; i++) nineteen.pages.add(i);
        DocGroup twoLeft = new DocGroup("j", "d");
        twoLeft.pages.add(20);
        twoLeft.pages.add(21);
        assertTrue(MailingAssembler.isDegenerate(List.of(nineteen, twoLeft), 21));

        DocGroup eighteen = new DocGroup("k", "d");
        for (int i = 1; i <= 18; i++) eighteen.pages.add(i);
        DocGroup threeLeft = new DocGroup("l", "d");
        threeLeft.pages.add(19);
        threeLeft.pages.add(20);
        threeLeft.pages.add(21);
        assertFalse(MailingAssembler.isDegenerate(List.of(eighteen, threeLeft), 21));
    }
}
