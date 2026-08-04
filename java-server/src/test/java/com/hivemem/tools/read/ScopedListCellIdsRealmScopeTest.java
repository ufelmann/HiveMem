package com.hivemem.tools.read;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.auth.ToolPermissionService;
import com.hivemem.search.CellSelector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;

/**
 * H2: {@code list_cell_ids} is the outlier among the realm-injected reads — its handler reads
 * only {@code where} and ignores a top-level {@code realm}, so the rewrite's "caller pinned a
 * realm, the DB already filters" shortcut left the selector completely unrestricted. The
 * response filter drops foreign {@code ids} but {@code total} passes through, so a realm-scoped
 * token learned the GLOBAL cell count and paginated over the unscoped set.
 */
class ScopedListCellIdsRealmScopeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ReadToolService readToolService = Mockito.mock(ReadToolService.class);
    private final ListCellIdsToolHandler handler = new ListCellIdsToolHandler(readToolService);
    private final ToolPermissionService permissions = new ToolPermissionService();

    private AuthPrincipal scoped() {
        return new AuthPrincipal("t", AuthRole.AGENT, null,
                List.of("dracul-research"), List.of("dracul-research"));
    }

    /** Exactly what the dispatcher does for a tool call. */
    private Object dispatch(String argsJson) {
        JsonNode args = MAPPER.readTree(argsJson);
        JsonNode rewritten = permissions.rewriteReadArgs(scoped(), "list_cell_ids", args);
        return handler.call(scoped(), rewritten);
    }

    private CellSelector capturedSelector() {
        ArgumentCaptor<CellSelector> selector = ArgumentCaptor.forClass(CellSelector.class);
        verify(readToolService).listCellIds(selector.capture(), anyInt(), anyInt());
        return selector.getValue();
    }

    @Test
    void topLevelRealmIsFoldedIntoTheSelectorInsteadOfBeingDropped() {
        dispatch("{\"realm\":\"dracul-research\"}");

        assertThat(capturedSelector().realm()).isEqualTo("dracul-research");
    }

    @Test
    void scopedTokenNeverReachesTheDbWithoutARealmPredicate() {
        dispatch("{\"realm\":\"dracul-research\",\"limit\":50}");

        CellSelector selector = capturedSelector();
        assertThat(selector.realm() != null || selector.realmIn() != null)
                .as("a realm-scoped token must never query the DB without a realm predicate")
                .isTrue();
    }

    @Test
    void withoutAnyRealmTheReadRealmsAreInjected() {
        dispatch("{}");

        assertThat(capturedSelector().realmIn()).containsExactly("dracul-research");
    }

    @Test
    void stringifiedJsonNullWhereIsScopedToReadRealms() {
        dispatch("{\"where\":\"null\"}");

        assertThat(capturedSelector().realmIn()).containsExactly("dracul-research");
    }
}
