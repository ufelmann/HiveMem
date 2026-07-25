package com.hivemem.summarize;

import com.hivemem.extraction.FactSpec;
import com.hivemem.llm.LlmCallCost;

import java.util.List;

public record SummaryResult(
        String title,
        String summary,
        List<String> keyPoints,
        String insight,
        List<String> tags,
        String documentType,
        List<FactSpec> facts,
        String language,
        boolean taxRelevant,
        LlmCallCost cost
) {
    public SummaryResult {
        keyPoints = keyPoints == null ? List.of() : List.copyOf(keyPoints);
        tags = tags == null ? List.of() : List.copyOf(tags);
        facts = facts == null ? List.of() : List.copyOf(facts);
    }
}
