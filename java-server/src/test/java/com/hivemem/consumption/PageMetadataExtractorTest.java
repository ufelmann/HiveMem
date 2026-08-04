package com.hivemem.consumption;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

class PageMetadataExtractorTest {

    private static final byte[] PNG = new byte[] {1, 2, 3};

    @Test
    void parsesAllFields() {
        VisionMultiClient vm = mock(VisionMultiClient.class);
        when(vm.group(eq("documents"), anyString(), anyList())).thenReturn("""
                {"sender":"SYNTHETIC ENERGY GmbH","date":"01.01.2000","page_label":"1/3",
                 "doc_type":"letter","reference":"SYNTHETIC-REF-0001","summary":"Order confirmation.",
                 "blank":false}""");
        PageMetadataExtractor.PageMetadata m =
                new PageMetadataExtractor(vm).extract("documents", 16, PNG, false);
        assertEquals(16, m.page());
        assertEquals("SYNTHETIC ENERGY GmbH", m.sender());
        assertEquals("01.01.2000", m.date());
        assertEquals("1/3", m.pageLabel());
        assertEquals("letter", m.docType());
        assertEquals("SYNTHETIC-REF-0001", m.reference());
        assertEquals("Order confirmation.", m.summary());
        assertFalse(m.blank());
        verify(vm).group(eq("documents"), anyString(), argThat(imgs -> imgs.size() == 1));
    }

    @Test
    void jsonNullsBecomeJavaNulls() {
        VisionMultiClient vm = mock(VisionMultiClient.class);
        when(vm.group(anyString(), anyString(), anyList())).thenReturn(
                "{\"sender\":null,\"date\":null,\"page_label\":null,\"doc_type\":\"blank\","
                        + "\"reference\":null,\"summary\":\"Blank back side.\",\"blank\":true}");
        PageMetadataExtractor.PageMetadata m =
                new PageMetadataExtractor(vm).extract("documents", 11, PNG, false);
        assertNull(m.sender());
        assertNull(m.date());
        assertNull(m.pageLabel());
        assertTrue(m.blank());
    }

    @Test
    void failureRetriesOnceThenReturnsNullRow() {
        VisionMultiClient vm = mock(VisionMultiClient.class);
        when(vm.group(anyString(), anyString(), anyList())).thenThrow(new RuntimeException("boom"));
        PageMetadataExtractor.PageMetadata m =
                new PageMetadataExtractor(vm).extract("documents", 4, PNG, false);
        assertEquals(4, m.page());
        assertNull(m.sender());
        assertNull(m.docType());
        assertFalse(m.blank());
        verify(vm, times(2)).group(anyString(), anyString(), anyList());
    }

    /** A page whose metadata could not be extracted must say so. Without this flag the null-row is
     *  indistinguishable from a genuinely empty page, and the batch looks healthy. */
    @Test
    void twoFailedAttemptsProduceADegradedRow() {
        VisionMultiClient vision = mock(VisionMultiClient.class);
        when(vision.group(anyString(), anyString(), anyList()))
                .thenReturn("I don't have permission to read the image file.");

        var meta = new PageMetadataExtractor(vision).extract("documents", 7, new byte[]{1, 2, 3}, false);

        assertTrue(meta.degraded(), "a null-row must be marked degraded");
        assertEquals(7, meta.page());
    }

    /** The belt for the prose reply: on a white page the model sometimes answers with prose instead
     *  of JSON, which fails both attempts. With the pixel finding in hand that is not a degradation
     *  — this was the sole cause of both degraded pages in the verification run. It is not blank
     *  either: blank is a delete list and there is no model verdict here, so during a provider
     *  outage the page must survive and let the 0.995 post-check have the last word. */
    @Test
    void twoFailedAttemptsOnAPixelBlankPageAreNeitherBlankNorDegraded() {
        VisionMultiClient vision = mock(VisionMultiClient.class);
        when(vision.group(anyString(), anyString(), anyList()))
                .thenReturn("I need you to provide the file path to the scanned document image.");

        var meta = new PageMetadataExtractor(vision).extract("documents", 7, new byte[]{1, 2, 3}, true);

        assertFalse(meta.blank(), "no model verdict, so the page must not reach the delete list");
        assertFalse(meta.degraded(), "and it is not a degradation either");
        assertEquals("blank", meta.docType(), "the pixel finding still lands in the metadata");
        assertEquals(7, meta.page());
        verify(vision, times(2)).group(anyString(), anyString(), anyList());
    }

    @Test
    void aParsedRowIsNotDegraded() {
        VisionMultiClient vision = mock(VisionMultiClient.class);
        when(vision.group(anyString(), anyString(), anyList()))
                .thenReturn("{\"sender\":\"SYNTHETIC INSURER\",\"blank\":false}");

        var meta = new PageMetadataExtractor(vision).extract("documents", 3, new byte[]{1}, false);

        assertFalse(meta.degraded());
    }

    /** A reply that parses but carries nothing (e.g. {@code {}}) is just as useless to assembly as
     *  an exception: every identifying field is null and the page was not classified blank. */
    @Test
    void anEmptyParsedReplyIsDegraded() {
        VisionMultiClient vision = mock(VisionMultiClient.class);
        when(vision.group(anyString(), anyString(), anyList())).thenReturn("{}");

        var meta = new PageMetadataExtractor(vision).extract("documents", 5, new byte[]{1}, false);

        assertTrue(meta.degraded(), "an all-null successful parse must still be flagged degraded");
    }

    /** A genuinely blank page is a successful classification, not a failure to extract anything —
     *  it must never be conflated with degraded. */
    @Test
    void aBlankPageReplyIsNotDegraded() {
        VisionMultiClient vision = mock(VisionMultiClient.class);
        when(vision.group(anyString(), anyString(), anyList())).thenReturn("{\"blank\":true}");

        var meta = new PageMetadataExtractor(vision).extract("documents", 6, new byte[]{1}, false);

        assertTrue(meta.blank());
        assertFalse(meta.degraded());
    }
}
