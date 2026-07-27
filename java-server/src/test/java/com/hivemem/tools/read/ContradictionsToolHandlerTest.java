package com.hivemem.tools.read;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

/**
 * Thin-adapter unit test for {@link ContradictionsToolHandler}: no Spring context, no DB.
 * {@link ContradictionService} is mocked — the service's own defaulting/clamping behaviour is
 * pinned by {@code ContradictionServiceIT}, not re-tested here.
 */
class ContradictionsToolHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AuthPrincipal PRINCIPAL = new AuthPrincipal("test", AuthRole.READER);

    private ContradictionService service;
    private ContradictionsToolHandler handler;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(ContradictionService.class);
        handler = new ContradictionsToolHandler(service);
    }

    @Test
    void name() {
        assertThat(handler.name()).isEqualTo("contradictions");
    }

    @Test
    @SuppressWarnings("unchecked")
    void inputSchemaDeclaresOptionalStatusSubjectAndLimit() {
        Map<String, Object> schema = handler.inputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(properties).containsKeys("status", "subject", "limit");
        assertThat(schema.get("required")).isNull();

        Map<String, Object> statusProp = (Map<String, Object>) properties.get("status");
        assertThat((List<String>) statusProp.get("enum")).containsExactlyInAnyOrder(
                "in_flight", "retryable", "pending", "resolved",
                "dismissed", "superseded", "not_contradictory", "deferred");

        Map<String, Object> limitProp = (Map<String, Object>) properties.get("limit");
        assertThat(limitProp.get("minimum")).isEqualTo(1);
        assertThat(limitProp.get("maximum")).isEqualTo(500);
    }

    @Test
    void callDelegatesAllThreeArgumentsToService() {
        JsonNode args = MAPPER.readTree("""
                {"status":"resolved","subject":"alice","limit":10}
                """);

        handler.call(PRINCIPAL, args);

        Mockito.verify(service).list("resolved", "alice", 10);
    }

    @Test
    void callWithNoArgumentsPassesAllNulls() {
        JsonNode args = MAPPER.readTree("{}");

        handler.call(PRINCIPAL, args);

        Mockito.verify(service).list(isNull(), isNull(), isNull());
    }

    /**
     * Without this guard, {@code contradictions(status="open")} would silently pass "open"
     * through to {@code ContradictionRepository.list}, which binds it verbatim into a {@code WHERE
     * fc.status = ?} clause — matching nothing and returning an empty list rather than an error.
     * An LLM caller would then conclude there are no contradictions instead of self-correcting the
     * typo'd status value.
     */
    @Test
    void rejectsAnUnknownStatusNamingTheAllowedOnes() {
        JsonNode args = MAPPER.readTree("""
                {"status":"open"}
                """);

        assertThatThrownBy(() -> handler.call(PRINCIPAL, args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("in_flight")
                .hasMessageContaining("deferred");
        Mockito.verifyNoInteractions(service);
    }

    @Test
    void callReturnsExactlyWhatTheServiceReturns() {
        List<Map<String, Object>> serviceResult = List.of(Map.of("id", "x"));
        Mockito.when(service.list(eq("pending"), isNull(), isNull())).thenReturn(serviceResult);
        JsonNode args = MAPPER.readTree("""
                {"status":"pending"}
                """);

        Object result = handler.call(PRINCIPAL, args);

        assertThat(result).isSameAs(serviceResult);
    }
}
