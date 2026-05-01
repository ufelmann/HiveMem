package com.hivemem.summarize;

import java.util.List;

public record SummaryResult(
        String summary,
        List<String> keyPoints,
        String insight,
        List<String> tags,
        int inputTokens,
        int outputTokens
) {}
