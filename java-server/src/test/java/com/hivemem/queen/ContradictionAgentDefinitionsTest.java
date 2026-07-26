package com.hivemem.queen;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContradictionAgentDefinitionsTest {

    private QueenProperties props() {
        QueenProperties p = new QueenProperties();
        p.setWebhookToken("wt");
        p.setContradictionWebhookToken("cwt");
        p.setHivememBaseUrl("http://hivemem:8421");
        return p;
    }

    @Test
    void contradictionJudgeHasExpectedShape() {
        Map<String, Object> def = new AgentDefinitions(props()).contradictionJudge();

        assertThat(def.get("name")).isEqualTo(AgentDefinitions.CONTRADICTION_JUDGE_NAME);
        assertThat(def.get("model_purpose")).isEqualTo("contradiction_judge");
        assertThat((List<?>) def.get("tools")).isEmpty();
        assertThat(def.get("max_turns")).isEqualTo(1);
        assertThat(def.get("max_run_seconds")).isEqualTo(120);
        assertThat((String) def.get("completion_webhook")).endsWith("/vistierie/contradiction/done");
        assertThat(def.get("completion_webhook_token")).isEqualTo("cwt");
        assertThat(def.get("webhook_token")).isEqualTo("wt");
    }

    @Test
    void predicateCardinalityJudgeHasExpectedShape() {
        Map<String, Object> def = new AgentDefinitions(props()).predicateCardinalityJudge();

        assertThat(def.get("name")).isEqualTo(AgentDefinitions.CARDINALITY_JUDGE_NAME);
        assertThat(def.get("model_purpose")).isEqualTo("predicate_cardinality");
        assertThat((List<?>) def.get("tools")).isEmpty();
        assertThat(def.get("max_turns")).isEqualTo(1);
        assertThat(def.get("max_run_seconds")).isEqualTo(120);
        assertThat((String) def.get("completion_webhook")).endsWith("/vistierie/cardinality/done");
        assertThat(def.get("completion_webhook_token")).isEqualTo("cwt");
        assertThat(def.get("webhook_token")).isEqualTo("wt");
    }

    @Test
    void judgesArePinnedApart() {
        AgentDefinitions defs = new AgentDefinitions(props());
        Map<String, Object> contradiction = defs.contradictionJudge();
        Map<String, Object> cardinality = defs.predicateCardinalityJudge();

        assertThat(contradiction.get("model_purpose")).isNotEqualTo(cardinality.get("model_purpose"));
        assertThat(contradiction.get("completion_webhook")).isNotEqualTo(cardinality.get("completion_webhook"));
    }

    @Test
    void cardinalityPromptWarnsAgainstCountBasedJudgement() {
        AgentDefinitions defs = new AgentDefinitions(props());
        Map<String, Object> cardinality = defs.predicateCardinalityJudge();
        String prompt = ((String) cardinality.get("system_prompt")).toLowerCase();

        assertThat(prompt).contains("count");
        assertThat(prompt).contains("semantic");
    }

    @Test
    void cardinalityPromptDescribesTheActualPayloadFields() {
        AgentDefinitions defs = new AgentDefinitions(props());
        String prompt = ((String) defs.predicateCardinalityJudge().get("system_prompt")).toLowerCase();

        // PredicatePayload carries only `predicate` and `sample_objects` — the prompt must not
        // describe fields the payload no longer sends (max_objects, sample_subjects, a
        // description field), and must treat sample_objects as usable semantic evidence.
        assertThat(prompt).contains("sample_objects");
        assertThat(prompt).doesNotContain("max_objects");
        assertThat(prompt).doesNotContain("sample_subjects");
        assertThat(prompt).doesNotContain("short description");
    }

    @Test
    void contradictionPromptStatesTheFreshnessFirewall() {
        AgentDefinitions defs = new AgentDefinitions(props());
        String contradictionPrompt = ((String) defs.contradictionJudge().get("system_prompt")).toLowerCase();

        // Positive assertions on the load-bearing guarantee itself, not on absent vocabulary:
        // a prompt can dodge any given word ("newer", "timestamp", ...) while still smuggling in
        // a freshness decision, and a stronger firewall wording could trip a negative-word test.
        assertThat(contradictionPrompt).contains("must not");
        assertThat(contradictionPrompt).contains("attempt to decide");
        assertThat(contradictionPrompt).contains("handled separately, in code");
    }

    @SuppressWarnings("unchecked")
    @Test
    void outputSchemasRequireVerdictsArray() {
        AgentDefinitions defs = new AgentDefinitions(props());

        Map<String, Object> contradictionSchema = (Map<String, Object>) defs.contradictionJudge().get("output_schema");
        assertThat((List<String>) contradictionSchema.get("required")).contains("verdicts");
        Map<String, Object> contradictionProps = (Map<String, Object>) contradictionSchema.get("properties");
        Map<String, Object> contradictionVerdicts = (Map<String, Object>) contradictionProps.get("verdicts");
        assertThat(contradictionVerdicts.get("type")).isEqualTo("array");
        Map<String, Object> contradictionItem = (Map<String, Object>) contradictionVerdicts.get("items");
        assertThat((List<String>) contradictionItem.get("required"))
                .contains("pair_id", "contradiction", "confidence");

        Map<String, Object> cardinalitySchema = (Map<String, Object>) defs.predicateCardinalityJudge().get("output_schema");
        assertThat((List<String>) cardinalitySchema.get("required")).contains("verdicts");
        Map<String, Object> cardinalityProps = (Map<String, Object>) cardinalitySchema.get("properties");
        Map<String, Object> cardinalityVerdicts = (Map<String, Object>) cardinalityProps.get("verdicts");
        assertThat(cardinalityVerdicts.get("type")).isEqualTo("array");
        Map<String, Object> cardinalityItem = (Map<String, Object>) cardinalityVerdicts.get("items");
        assertThat((List<String>) cardinalityItem.get("required"))
                .contains("predicate", "cardinality", "confidence");

        Map<String, Object> cardinalityProp = (Map<String, Object>) cardinalityItem.get("properties");
        Map<String, Object> cardinalityEnumField = (Map<String, Object>) cardinalityProp.get("cardinality");
        assertThat((List<String>) cardinalityEnumField.get("enum"))
                .containsExactlyInAnyOrder("single_valued", "multi_valued");
    }
}
