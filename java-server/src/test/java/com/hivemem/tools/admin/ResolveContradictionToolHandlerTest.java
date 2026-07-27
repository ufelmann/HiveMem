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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Thin-adapter unit test for {@link ResolveContradictionToolHandler}. The enum guard tested here
 * ({@code keep}) is the handler's OWN guard — {@link ContradictionService#resolve} has its own,
 * separate {@code default -> throw} arm for the same value, exercised at the service level by
 * {@code ContradictionServiceIT.resolveRejectsAnUnknownKeepValue}. A handler-only test proves
 * nothing about the service's guard and vice versa (this test never reaches the service — it
 * mocks it — so it cannot exercise the service's own arm), which is why that IT exists as a
 * separate, direct call to {@link ContradictionService#resolve}.
 */
class ResolveContradictionToolHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AuthPrincipal PRINCIPAL = new AuthPrincipal("test", AuthRole.ADMIN);

    private ContradictionService service;
    private ResolveContradictionToolHandler handler;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(ContradictionService.class);
        handler = new ResolveContradictionToolHandler(service);
    }

    @Test
    void name() {
        assertThat(handler.name()).isEqualTo("resolve_contradiction");
    }

    @Test
    @SuppressWarnings("unchecked")
    void inputSchemaRequiresIdKeepAndReason() {
        Map<String, Object> schema = handler.inputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(properties).containsKeys("id", "keep", "reason");
        assertThat((List<String>) schema.get("required")).containsExactlyInAnyOrder("id", "keep", "reason");

        Map<String, Object> keepProp = (Map<String, Object>) properties.get("keep");
        assertThat((List<String>) keepProp.get("enum"))
                .containsExactlyInAnyOrder("fact_a", "fact_b", "both", "requeue");
    }

    @Test
    void callDelegatesToServiceResolve() {
        UUID id = UUID.randomUUID();
        JsonNode args = MAPPER.readTree("""
                {"id":"%s","keep":"fact_a","reason":"more recent"}
                """.formatted(id));

        handler.call(PRINCIPAL, args);

        Mockito.verify(service).resolve(id, "fact_a", "more recent");
    }

    @Test
    void rejectsAnUnknownKeepValueNamingTheAllowedOnes() {
        JsonNode args = MAPPER.readTree("""
                {"id":"%s","keep":"fact_c","reason":"whatever"}
                """.formatted(UUID.randomUUID()));

        assertThatThrownBy(() -> handler.call(PRINCIPAL, args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fact_a")
                .hasMessageContaining("fact_b")
                .hasMessageContaining("both")
                .hasMessageContaining("requeue");
        Mockito.verifyNoInteractions(service);
    }

    @Test
    void rejectsAMalformedUuid() {
        JsonNode args = MAPPER.readTree("""
                {"id":"not-a-uuid","keep":"fact_a","reason":"whatever"}
                """);

        assertThatThrownBy(() -> handler.call(PRINCIPAL, args))
                .isInstanceOf(IllegalArgumentException.class);
        Mockito.verifyNoInteractions(service);
    }

    @Test
    void requiresReason() {
        JsonNode args = MAPPER.readTree("""
                {"id":"%s","keep":"fact_a"}
                """.formatted(UUID.randomUUID()));

        assertThatThrownBy(() -> handler.call(PRINCIPAL, args))
                .isInstanceOf(IllegalArgumentException.class);
        Mockito.verifyNoInteractions(service);
    }
}
