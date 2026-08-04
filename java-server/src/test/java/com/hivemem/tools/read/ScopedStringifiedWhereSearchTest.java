package com.hivemem.tools.read;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.auth.ToolPermissionService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

/**
 * H1 regression: a realm-scoped token plus a stringified {@code where} must reach the search
 * handler with every caller key intact. This is the exact production path
 * (dispatcher: {@code rewriteReadArgs} -> {@code handler.call}) that dropped the filter in
 * Dracul run 8C9A4F0B8EFC4C159626538ED6C5FC91, where two calls with different symbol filters
 * returned the byte-identical five newest cells of the realm.
 */
class ScopedStringifiedWhereSearchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ReadToolService readToolService = Mockito.mock(ReadToolService.class);
    private final SearchToolHandler handler = new SearchToolHandler(readToolService);
    private final ToolPermissionService permissions = new ToolPermissionService();

    private AuthPrincipal scoped() {
        return new AuthPrincipal("t", AuthRole.AGENT, null,
                List.of("dracul-research"), List.of("dracul-research"));
    }

    /** Exactly what the dispatcher does for a tool call. */
    private Object dispatch(String argsJson) {
        JsonNode args = MAPPER.readTree(argsJson);
        JsonNode rewritten = permissions.rewriteReadArgs(scoped(), "search", args);
        return handler.call(scoped(), rewritten);
    }

    @Test
    void stringifiedWhereFilterSurvivesTheRealmRewrite() {
        dispatch("{\"limit\":\"5\",\"where\":\"{\\\"realm\\\": \\\"dracul-research\\\", \\\"topic\\\": \\\"EA\\\"}\"}");

        verify(readToolService).searchBrowse(
                eq(5), eq("dracul-research"), isNull(), eq("EA"), any(), isNull(), isNull(), isNull());
    }

    @Test
    void differentStringifiedFiltersProduceDifferentQueries() {
        dispatch("{\"limit\":\"5\",\"where\":\"{\\\"realm\\\": \\\"dracul-research\\\", \\\"topic\\\": \\\"FERG\\\"}\"}");

        verify(readToolService).searchBrowse(
                anyInt(), eq("dracul-research"), isNull(), eq("FERG"), any(), isNull(), isNull(), isNull());
    }

    @Test
    void preservedUnknownWhereKeySurfacesAsAnErrorInsteadOfBeingIgnored() {
        // The verbatim production call. `symbol` is not a CellSelector field; with the key
        // preserved the request must fail loudly rather than silently returning realm rows.
        assertThatThrownBy(() ->
                dispatch("{\"limit\":\"5\",\"where\":\"{\\\"realm\\\": \\\"dracul-research\\\", \\\"symbol\\\": \\\"EA\\\"}\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown where field: symbol");
        Mockito.verifyNoInteractions(readToolService);
    }

    @Test
    void malformedStringifiedWhereFailsLoudlyAndFetchesNothing() {
        assertThatThrownBy(() -> dispatch("{\"query\":\"x\",\"where\":\"not json\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid where");
        Mockito.verifyNoInteractions(readToolService);
    }
}
