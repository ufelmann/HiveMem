package com.hivemem.contradiction;

import java.util.UUID;

/**
 * One candidate pair sent to the {@code contradiction-judge} agent. Component names are
 * snake_case on purpose — they serialise verbatim to the JSON the agent's output schema and
 * prompt refer to (see {@code AgentDefinitions#contradictionJudge}); do not rename to camelCase.
 *
 * <p>Deliberately carries no timestamp or provenance field (no {@code valid_from}, {@code
 * detected_at}, confidence of either fact, etc.): the judge decides only whether {@code object_a}
 * and {@code object_b} denote the same real-world thing, and picking a winner between them by
 * freshness is handled afterwards in code, not by the model.
 */
public record PairPayload(UUID pair_id, String subject, String predicate, String object_a, String object_b) {}
