package com.hivemem.extraction;

import java.util.List;

/** Profile loaded from src/main/resources/extraction-profiles/{type}.yaml. */
public record ExtractionProfile(
        String type,
        String prompt,
        List<String> requiredFacts,
        List<String> optionalFacts,
        String summaryTemplate,
        List<String> tagsToApply
) {
    public ExtractionProfile {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("ExtractionProfile.type must not be blank");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("ExtractionProfile.prompt must not be blank");
        }
        requiredFacts = requiredFacts == null ? List.of() : List.copyOf(requiredFacts);
        optionalFacts = optionalFacts == null ? List.of() : List.copyOf(optionalFacts);
        tagsToApply = tagsToApply == null ? List.of() : List.copyOf(tagsToApply);
    }
}
