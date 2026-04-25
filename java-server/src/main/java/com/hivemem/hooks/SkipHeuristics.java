package com.hivemem.hooks;

import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class SkipHeuristics {

    public record SkipDecision(boolean skip, String reason) {
        public static SkipDecision skip(String reason) { return new SkipDecision(true, reason); }
        public static SkipDecision keep() { return new SkipDecision(false, ""); }
    }

    private static final Set<String> META_PHRASES = Set.of(
            "ok", "okay", "yes", "no", "thanks", "thank you", "continue", "go on",
            "try again", "make it shorter", "weiter", "danke", "ja", "nein"
    );

    public SkipDecision evaluate(String prompt) {
        if (prompt == null) return SkipDecision.skip("null");
        String trimmed = prompt.trim();
        if (trimmed.isEmpty()) return SkipDecision.skip("empty");
        String lower = trimmed.toLowerCase();
        if (lower.startsWith("!nomem")) return SkipDecision.skip("opt_out");
        if (lower.startsWith("!mem ")) return SkipDecision.keep();
        if (META_PHRASES.contains(lower)) return SkipDecision.skip("meta");
        if (isPureCodeBlock(trimmed)) return SkipDecision.skip("code_only");
        long words = trimmed.split("\\s+").length;
        if (words < 4) return SkipDecision.skip("too_short");
        return SkipDecision.keep();
    }

    private boolean isPureCodeBlock(String s) {
        return s.startsWith("```") && s.endsWith("```");
    }
}
