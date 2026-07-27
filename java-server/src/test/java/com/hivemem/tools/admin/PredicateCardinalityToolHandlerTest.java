package com.hivemem.tools.admin;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.contradiction.ContradictionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.isNull;

/**
 * Thin-adapter unit test for {@link PredicateCardinalityToolHandler}. Like {@link
 * ResolveContradictionToolHandlerTest}, the {@code set} enum guard here is the HANDLER's own
 * guard; {@link ContradictionService#setCardinality} has no enum guard of its own (the DB CHECK
 * constraint on {@code predicate_cardinality.cardinality} is the backstop there), so this handler
 * guard is not redundant with anything at the service layer.
 */
class PredicateCardinalityToolHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AuthPrincipal PRINCIPAL = new AuthPrincipal("test", AuthRole.ADMIN);

    private ContradictionService service;
    private PredicateCardinalityToolHandler handler;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(ContradictionService.class);
        handler = new PredicateCardinalityToolHandler(service);
    }

    @Test
    void name() {
        assertThat(handler.name()).isEqualTo("predicate_cardinality");
    }

    @Test
    @SuppressWarnings("unchecked")
    void inputSchemaDeclaresAllThreeFieldsAsOptional() {
        Map<String, Object> schema = handler.inputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(properties).containsKeys("predicate", "set", "reason");
        assertThat(schema.get("required")).isNull();

        Map<String, Object> setProp = (Map<String, Object>) properties.get("set");
        assertThat((List<String>) setProp.get("enum")).containsExactlyInAnyOrder("single_valued", "multi_valued");
    }

    @Test
    void noSetArgumentListsVerdicts() {
        JsonNode args = MAPPER.readTree("""
                {"predicate":"lives_in"}
                """);

        handler.call(PRINCIPAL, args);

        Mockito.verify(service).listCardinality("lives_in");
        Mockito.verify(service, Mockito.never()).setCardinality(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
    }

    @Test
    void noArgumentsAtAllListsAllVerdicts() {
        JsonNode args = MAPPER.readTree("{}");

        handler.call(PRINCIPAL, args);

        Mockito.verify(service).listCardinality(isNull());
    }

    @Test
    void setArgumentDelegatesToSetCardinality() {
        JsonNode args = MAPPER.readTree("""
                {"predicate":"lives_in","set":"single_valued","reason":"one home at a time"}
                """);

        handler.call(PRINCIPAL, args);

        Mockito.verify(service).setCardinality("lives_in", "single_valued", "one home at a time");
        Mockito.verify(service, Mockito.never()).listCardinality(Mockito.any());
    }

    @Test
    void rejectsAnUnknownSetValueNamingTheAllowedOnes() {
        JsonNode args = MAPPER.readTree("""
                {"predicate":"lives_in","set":"maybe_valued","reason":"whatever"}
                """);

        assertThatThrownBy(() -> handler.call(PRINCIPAL, args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("single_valued")
                .hasMessageContaining("multi_valued");
        Mockito.verifyNoInteractions(service);
    }

    @Test
    void setWithoutReasonIsRejected() {
        JsonNode args = MAPPER.readTree("""
                {"predicate":"lives_in","set":"single_valued"}
                """);

        assertThatThrownBy(() -> handler.call(PRINCIPAL, args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason")
                .hasMessageContaining("'set'");
        Mockito.verifyNoInteractions(service);
    }

    @Test
    void setWithoutPredicateIsRejected() {
        JsonNode args = MAPPER.readTree("""
                {"set":"single_valued","reason":"whatever"}
                """);

        assertThatThrownBy(() -> handler.call(PRINCIPAL, args))
                .isInstanceOf(IllegalArgumentException.class);
        Mockito.verifyNoInteractions(service);
    }
}
