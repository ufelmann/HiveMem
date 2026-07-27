package com.hivemem.contradiction;

import java.util.List;

/**
 * One predicate sent to the {@code predicate-cardinality-judge} agent. Component names are
 * snake_case on purpose — they serialise verbatim to the JSON the agent's output schema and
 * prompt refer to (see {@code AgentDefinitions#predicateCardinalityJudge}); do not rename to
 * camelCase.
 *
 * <p>Deliberately does NOT carry {@code sample_subjects} or an object count (e.g. {@code
 * max_objects}). The judge must decide cardinality from the semantics of the predicate name and
 * the kind of values it holds, never from how many objects are on record — a high object count is
 * exactly what a struggling single-valued predicate looks like, i.e. precisely the inconsistency
 * this feature exists to catch. A prompt instruction saying "ignore this number" while the number
 * still rides in the payload is the "don't think of an elephant" failure mode: what the judge must
 * not use, it must not receive. Do not add either field back.
 */
public record PredicatePayload(String predicate, List<String> sample_objects) {}
