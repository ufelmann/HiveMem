package com.hivemem.summarize;

public class NeedsSummaryDecider {

    private final int thresholdChars;

    public NeedsSummaryDecider(int thresholdChars) {
        this.thresholdChars = thresholdChars;
    }

    public boolean needsSummary(String content, String summary) {
        if (summary != null && !summary.isBlank()) return false;
        if (content == null) return false;
        return content.length() > thresholdChars;
    }
}
