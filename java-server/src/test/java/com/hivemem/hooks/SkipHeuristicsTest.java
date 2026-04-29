package com.hivemem.hooks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SkipHeuristicsTest {

    private final SkipHeuristics h = new SkipHeuristics();

    @ParameterizedTest
    @ValueSource(strings = {"ok", "yes", "no", "continue", "go on", "thanks", "weiter", "danke"})
    void shortMetaPhrasesAreSkipped(String prompt) {
        assertThat(h.evaluate(prompt).skip()).isTrue();
    }

    @Test
    void wordCountBelowFiveIsSkipped() {
        assertThat(h.evaluate("do that thing").skip()).isTrue();
        assertThat(h.evaluate("make it work please").skip()).isTrue();
    }

    @Test
    void pureCodeBlockIsSkipped() {
        assertThat(h.evaluate("```java\nint x = 1;\n```").skip()).isTrue();
    }

    @Test
    void nomemPrefixForcesSkip() {
        assertThat(h.evaluate("!nomem tell me about the architecture").skip()).isTrue();
    }

    @Test
    void normalQuestionIsNotSkipped() {
        assertThat(h.evaluate("What was the plan for project X phase 3?").skip()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "wie geht es dir heute",
            "wie läuft es so",
            "wie war dein tag",
            "hallo wie geht es dir heute",
            "guten morgen wie läuft es"
    })
    void socialStarterPhrasesAreSkipped(String prompt) {
        assertThat(h.evaluate(prompt).skip()).isTrue();
    }

    @Test
    void technicalQuestionWithFiveWordsIsNotSkipped() {
        assertThat(h.evaluate("erkläre den auth flow bitte").skip()).isFalse();
    }

    @Test
    void memPrefixForcesNoSkipEvenIfShort() {
        assertThat(h.evaluate("!mem ok").skip()).isFalse();
    }

    @Test
    void nullAndEmptyAreSkipped() {
        assertThat(h.evaluate(null).skip()).isTrue();
        assertThat(h.evaluate("").skip()).isTrue();
        assertThat(h.evaluate("   ").skip()).isTrue();
    }
}
