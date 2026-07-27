package com.hivemem.contradiction;

import java.util.List;
import java.util.UUID;

/**
 * The pair-judge completion callback envelope: {@code {run_id, status, output:{verdicts:[...]}}}.
 * Field names are snake_case on purpose — they deserialize directly from Vistierie's callback
 * JSON body, mirroring {@link RunCreated} and {@link PairPayload}.
 *
 * <p>{@code output} (and {@code output.verdicts}) is nullable: a run that fails before the judge
 * produces any output (e.g. a misconfigured {@code model_purpose} routing rule — see {@link
 * ContradictionService}'s Javadoc) still calls back, but with no output at all.
 */
public record PairVerdicts(String run_id, String status, Output output) {

    public record Output(List<Verdict> verdicts) {}

    /**
     * One judge verdict on a pair: {@code contradiction = true} means the two facts genuinely
     * conflict; {@code false} clears the pair.
     */
    public record Verdict(UUID pair_id, boolean contradiction, double confidence, String rationale) {}
}
