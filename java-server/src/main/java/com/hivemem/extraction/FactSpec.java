package com.hivemem.extraction;

/** A single fact returned by the LLM extractor: predicate-object-confidence triple. */
public record FactSpec(String predicate, String object, double confidence) {
    public FactSpec {
        if (predicate == null || predicate.isBlank()) {
            throw new IllegalArgumentException("FactSpec.predicate must not be blank");
        }
        if (object == null) {
            throw new IllegalArgumentException("FactSpec.object must not be null");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("FactSpec.confidence must be in [0,1], was " + confidence);
        }
    }
}
