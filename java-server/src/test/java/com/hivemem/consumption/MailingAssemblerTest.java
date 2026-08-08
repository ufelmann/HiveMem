package com.hivemem.consumption;

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
    void groupsAreOrderedByTheirLowestPageAndPagesAscending() {
        List<DocGroup> draw = List.of(g("late", 0.9, 9, 7), g("early", 0.9, 3, 1));
        List<DocGroup> out = MailingAssembler.consensus(List.of(draw, draw, draw),
                List.of(1, 3, 7, 9));
        assertEquals(List.of(1, 3), out.get(0).pages);
        assertEquals(List.of(7, 9), out.get(1).pages);
    }

    @Test
    void confidenceIsTheMinimumOverTheContributingDrawGroups() {
        List<DocGroup> a = List.of(g("a", 0.9, 1, 2));
        List<DocGroup> b = List.of(g("b", 0.4, 1, 2));
        List<DocGroup> c = List.of(g("c", 0.7, 1, 2));
        List<DocGroup> out = MailingAssembler.consensus(List.of(a, b, c), List.of(1, 2));
        assertEquals(1, out.size());
        assertEquals(0.4, out.get(0).minConfidence, 1e-9);
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
}
