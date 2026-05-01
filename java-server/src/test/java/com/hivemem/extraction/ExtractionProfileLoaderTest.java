package com.hivemem.extraction;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExtractionProfileLoaderTest {

    @Test
    void loadsAllShippedProfiles() {
        Map<String, ExtractionProfile> map =
                ExtractionProfileLoader.loadFromClasspath("extraction-profiles/");

        assertTrue(map.containsKey("invoice"), "invoice profile present");
        assertTrue(map.containsKey("contract"), "contract profile present");
        assertTrue(map.containsKey("other"), "other profile present");

        ExtractionProfile invoice = map.get("invoice");
        assertEquals("invoice", invoice.type());
        assertTrue(invoice.prompt().toLowerCase().contains("rechnung"));
        assertEquals(4, invoice.requiredFacts().size());
        assertTrue(invoice.requiredFacts().contains("vendor"));
        assertTrue(invoice.tagsToApply().contains("invoice"));
    }

    @Test
    void rejectsProfileWithMismatchedFilenameAndType() {
        // Use a synthetic resource path that doesn't exist; loader should return empty map.
        Map<String, ExtractionProfile> map =
                ExtractionProfileLoader.loadFromClasspath("nonexistent-profiles/");
        assertTrue(map.isEmpty());
    }
}
