package com.hivemem.extraction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PreClassifierTest {

    @Test
    void filenameContainingRechnungYieldsInvoice() {
        assertEquals("invoice",
                PreClassifier.guessType("application/pdf", "Stadtwerke-Rechnung-Mai.pdf", null));
    }

    @Test
    void filenameContainingInvoiceYieldsInvoice() {
        assertEquals("invoice",
                PreClassifier.guessType("application/pdf", "AcmeCorp_Invoice_2026-04.pdf", null));
    }

    @Test
    void filenameContainingVertragYieldsContract() {
        assertEquals("contract",
                PreClassifier.guessType("application/pdf", "Mietvertrag-2026.pdf", null));
    }

    @Test
    void head200KeywordRechnungsnummerYieldsInvoice() {
        assertEquals("invoice",
                PreClassifier.guessType("application/pdf", "scan001.pdf",
                        "Datum 2026-05-01 Rechnungsnummer 12345 Betrag 100 EUR"));
    }

    @Test
    void head200KeywordKuendigungsfristYieldsContract() {
        assertEquals("contract",
                PreClassifier.guessType("application/pdf", "doc.pdf",
                        "Vertrag zwischen X und Y. Kündigungsfrist drei Monate zum Quartalsende."));
    }

    @Test
    void unknownInputFallsBackToOther() {
        assertEquals("other",
                PreClassifier.guessType("application/pdf", "doc.pdf", "Hello world."));
    }

    @Test
    void imageMimeAlwaysReturnsOther() {
        assertEquals("other",
                PreClassifier.guessType("image/png", "Rechnung.png", "Rechnungsnummer"));
    }

    @Test
    void nullInputsAreSafe() {
        assertEquals("other", PreClassifier.guessType(null, null, null));
    }
}
