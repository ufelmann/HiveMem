package com.hivemem.extraction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExtractionProfileRegistryTest {

    @Test
    void resolvesKnownTypeOrFallsBackToOther() {
        ExtractionProfileRegistry registry = new ExtractionProfileRegistry();

        ExtractionProfile invoice = registry.resolve("invoice");
        assertEquals("invoice", invoice.type());

        ExtractionProfile unknown = registry.resolve("nonsense_type");
        assertEquals("other", unknown.type(), "unknown type falls back to 'other'");

        ExtractionProfile nullType = registry.resolve(null);
        assertEquals("other", nullType.type(), "null falls back to 'other'");
    }

    @Test
    void knownTypesIncludeAllShippedProfiles() {
        ExtractionProfileRegistry registry = new ExtractionProfileRegistry();
        assertTrue(registry.isKnown("invoice"));
        assertTrue(registry.isKnown("contract"));
        assertTrue(registry.isKnown("other"));
        assertFalse(registry.isKnown("garbage"));
    }
}
