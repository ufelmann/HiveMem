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
            "try again", "make it shorter", "weiter", "danke", "ja", "nein",
            "danke schön", "bitte", "super", "gut", "prima", "alles klar",
            "verstanden", "understood", "got it", "sure", "sounds good",
            "wie geht's", "wie gehts", "wie geht es", "wie läuft's", "wie läufts",
            "what's up", "whats up", "was ist los"
    );

    // Prompts starting with these (lowercased) are social/meta if they are short.
    private static final Set<String> SOCIAL_STARTERS = Set.of(
            "wie geht ", "wie läuft ", "wie war ", "was machst ", "was ist los",
            "hallo", "guten morgen", "guten tag", "guten abend", "hi ", "hey ",
            "bitte grüß", "schönen tag"
    );

    private static final int MIN_WORDS = 5;
    private static final int SOCIAL_STARTER_MAX_WORDS = 8;

    // Technical signals: if any match, the prompt is likely technical and should not be skipped.
    private static final String TECH_SIGNAL_PATTERN =
            "[A-Z][a-z]+[A-Z]|[a-z]+[A-Z]|[./\\\\@#]|`|\\d+\\.\\d+|[A-Z]{2,}[_a-z]";

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
        if (words < MIN_WORDS) return SkipDecision.skip("too_short");
        if (isSocialStarter(lower, words)) return SkipDecision.skip("social");
        return SkipDecision.keep();
    }

    private boolean isSocialStarter(String lower, long words) {
        if (words > SOCIAL_STARTER_MAX_WORDS) return false;
        for (String starter : SOCIAL_STARTERS) {
            if (lower.startsWith(starter)) return true;
        }
        return false;
    }

    private boolean isPureCodeBlock(String s) {
        return s.startsWith("```") && s.endsWith("```");
    }
}
