package com.hivemem.tools.read;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.auth.ToolPermissionService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
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

    /**
     * H1 regression of the H1 fix: {@code where:"null"} parses to a JSON null, which is "no
     * filter", not a malformed filter. Classifying it as an unparseable string handed it through
     * verbatim, the handler read the JSON null as "no where" — and the DB was hit with
     * {@code realm=null, realmIn=null}: an unscoped cross-realm scan for a scoped token.
     */
    @ParameterizedTest
    @ValueSource(strings = {"null", "  null  "})
    void stringifiedJsonNullWhereIsScopedToReadRealms(String where) {
        dispatch("{\"query\":\"secret\",\"where\":\"" + where + "\"}");

        verify(readToolService).search(
                eq("secret"), anyInt(), isNull(), isNull(), isNull(), any(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                isNull(), isNull(), eq(List.of("dracul-research")), anyBoolean());
    }

    /**
     * Scope must never widen: whatever the {@code where} looks like, a scoped token may not reach
     * the DB with neither {@code realm} nor {@code realm_in} set.
     */
    @Test
    void scopedTokenNeverReachesTheDbWithoutARealmPredicate() {
        dispatch("{\"query\":\"secret\",\"where\":\"null\"}");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> realmIn = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> realm = ArgumentCaptor.forClass(String.class);
        verify(readToolService).search(
                any(), anyInt(), realm.capture(), isNull(), isNull(), any(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                isNull(), isNull(), realmIn.capture(), anyBoolean());

        assertThat(realm.getValue() != null || realmIn.getValue() != null)
                .as("a realm-scoped token must never query the DB without a realm predicate")
                .isTrue();
        assertThat(realmIn.getValue()).containsExactly("dracul-research");
    }

    /**
     * Everything else that is not a JSON object must keep failing loudly, with zero DB calls —
     * the H1 fix must not turn these into a silently realm-only query.
     */
    @ParameterizedTest
    @ValueSource(strings = {"[1,2]", "5", "true", "", "not json at all"})
    void nonObjectStringifiedWhereStillFailsLoudlyWithNoDbCall(String where) {
        assertThatThrownBy(() -> dispatch("{\"query\":\"x\",\"where\":\"" + where + "\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        Mockito.verifyNoInteractions(readToolService);
    }
}
