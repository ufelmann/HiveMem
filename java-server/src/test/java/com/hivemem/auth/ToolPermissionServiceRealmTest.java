package com.hivemem.auth;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;

class ToolPermissionServiceRealmTest {
    private final ToolPermissionService svc = new ToolPermissionService();
    private final ObjectMapper mapper = new ObjectMapper();

    private AuthPrincipal scoped() {
        return new AuthPrincipal("t", AuthRole.WRITER, null,
                List.of("dracul-research", "dracul"), List.of("dracul-research"));
    }
    private AuthPrincipal unscoped() { return new AuthPrincipal("u", AuthRole.WRITER); }
    private JsonNode args(String json) throws Exception { return mapper.readTree(json); }

    // ---- WRITE ----
    @Test void addCellOwnRealmAllowed() throws Exception {
        assertThat(svc.realmDenial(scoped(), "add_cell", args("{\"realm\":\"dracul-research\"}"))).isEmpty();
    }
    @Test void addCellForeignRealmDenied() throws Exception {
        assertThat(svc.realmDenial(scoped(), "add_cell", args("{\"realm\":\"personal\"}"))).isPresent();
    }
    @Test void addCellReadOnlyRealmDenied_readIsNotWrite() throws Exception {
        assertThat(svc.realmDenial(scoped(), "add_cell", args("{\"realm\":\"dracul\"}"))).isPresent();
    }
    @Test void addCellMissingRealmDenied_noNullRealmEscape() throws Exception {
        // I1: an omitted realm must NOT default at the write path (would persist a null-realm
        // cell escaping write_realms) — a scoped write must name an explicit realm.
        assertThat(svc.realmDenial(scoped(), "add_cell", args("{\"content\":\"x\"}"))).isPresent();
    }
    @Test void addCellBlankRealmDenied() throws Exception {
        assertThat(svc.realmDenial(scoped(), "add_cell", args("{\"content\":\"x\",\"realm\":\"  \"}"))).isPresent();
    }
    @Test void updateBlueprintMissingRealmDenied() throws Exception {
        assertThat(svc.realmDenial(scoped(), "update_blueprint", args("{}"))).isPresent();
    }
    @Test void uploadAttachmentMissingRealmDenied() throws Exception {
        assertThat(svc.realmDenial(scoped(), "upload_attachment", args("{}"))).isPresent();
    }
    @Test void kgAddAllowedForScoped() throws Exception {
        assertThat(svc.realmDenial(scoped(), "kg_add", args("{}"))).isEmpty();
    }
    @Test void reclassifyDeniedForScoped() throws Exception {
        assertThat(svc.realmDenial(scoped(), "reclassify", args("{\"realm\":\"dracul-research\"}"))).isPresent();
    }
    @Test void reviseCellDeniedForScoped() throws Exception {
        assertThat(svc.realmDenial(scoped(), "revise_cell", args("{}"))).isPresent();
    }

    // ---- READ ----
    @Test void searchAllowedButFiltered() throws Exception {
        assertThat(svc.realmDenial(scoped(), "search", args("{\"query\":\"x\"}"))).isEmpty();
    }
    @Test void traverseDeniedForScoped() throws Exception {
        assertThat(svc.realmDenial(scoped(), "traverse", args("{}"))).isPresent();
    }
    @Test void dataQualityReportDeniedForScoped() throws Exception {
        assertThat(svc.realmDenial(scoped(), "data_quality_report", args("{}"))).isPresent();
    }
    @Test void readingListDeniedForScoped() throws Exception {
        assertThat(svc.realmDenial(scoped(), "reading_list", args("{}"))).isPresent();
    }
    @Test void searchKgGlobalAllowed() throws Exception {
        assertThat(svc.realmDenial(scoped(), "search_kg", args("{}"))).isEmpty();
    }
    @Test void contradictionsGlobalAllowed() throws Exception {
        // contradictions carries no realm of its own to filter a scoped token on — it belongs in
        // READ_GLOBAL_TOOLS alongside search_kg/time_machine/wake_up, not behind the fail-closed
        // READ_DENY_WHEN_SCOPED default.
        assertThat(svc.realmDenial(scoped(), "contradictions", args("{}"))).isEmpty();
    }
    @Test void listDrilldownForeignRealmDenied() throws Exception {
        assertThat(svc.realmDenial(scoped(), "list", args("{\"realm\":\"personal\"}"))).isPresent();
    }
    @Test void listDrilldownOwnRealmAllowed() throws Exception {
        assertThat(svc.realmDenial(scoped(), "list", args("{\"realm\":\"dracul\"}"))).isEmpty();
    }

    // ---- filterReadResponse ----
    @Test void searchResponseDropsForeignRows() throws Exception {
        JsonNode result = mapper.readTree(
                "[{\"id\":\"1\",\"realm\":\"dracul-research\"},{\"id\":\"2\",\"realm\":\"personal\"}]");
        JsonNode filtered = svc.filterReadResponse(scoped(), "search", args("{}"), result);
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).get("realm").asText()).isEqualTo("dracul-research");
    }
    @Test void getCellForeignRealmBecomesEmpty() throws Exception {
        JsonNode result = mapper.readTree("{\"id\":\"9\",\"realm\":\"work\"}");
        JsonNode filtered = svc.filterReadResponse(scoped(), "get_cell", args("{}"), result);
        assertThat(filtered.isNull() || filtered.isEmpty() || filtered.isMissingNode()).isTrue();
    }
    @Test void statusRealmsFilteredToReadRealms() throws Exception {
        JsonNode result = mapper.readTree(
                "{\"cells\":10,\"facts\":5,\"realms\":[{\"value\":\"dracul-research\"},{\"value\":\"personal\"}]}");
        JsonNode filtered = svc.filterReadResponse(scoped(), "status", args("{}"), result);
        assertThat(filtered.get("realms")).hasSize(1);
        assertThat(filtered.get("realms").get(0).get("value").asText()).isEqualTo("dracul-research");
    }

    // ---- C1: list realm-enumeration filter ----
    @Test void listRealmEnumerationDropsForeignRealms() throws Exception {
        JsonNode result = mapper.readTree(
                "[{\"value\":\"dracul-research\",\"label\":\"dracul-research\",\"cell_count\":3},"
                + "{\"value\":\"dracul\",\"label\":\"dracul\",\"cell_count\":1},"
                + "{\"value\":\"personal\",\"label\":\"personal\",\"cell_count\":9},"
                + "{\"value\":\"work\",\"label\":\"work\",\"cell_count\":2}]");
        JsonNode filtered = svc.filterReadResponse(scoped(), "list", args("{}"), result);
        assertThat(filtered).hasSize(2);
        for (JsonNode row : filtered) {
            assertThat(row.get("value").asText()).isIn("dracul-research", "dracul");
        }
    }
    @Test void listDrilldownResponseNotFiltered_signalsAreNotRealms() throws Exception {
        // list realm=dracul → signals whose `value` are signal names, must survive untouched.
        JsonNode result = mapper.readTree(
                "[{\"value\":\"facts\",\"label\":\"facts\",\"cell_count\":3},"
                + "{\"value\":\"events\",\"label\":\"events\",\"cell_count\":1}]");
        JsonNode filtered = svc.filterReadResponse(scoped(), "list",
                args("{\"realm\":\"dracul\"}"), result);
        assertThat(filtered).hasSize(2);
    }

    // ---- C1: facet_count realm-bucket filter + precheck ----
    @Test void facetCountRealmBucketsDropForeignRealms() throws Exception {
        JsonNode result = mapper.readTree(
                "{\"realm\":[{\"value\":\"dracul-research\",\"count\":3},"
                + "{\"value\":\"personal\",\"count\":9}],"
                + "\"signal\":[{\"value\":\"facts\",\"count\":5}]}");
        JsonNode filtered = svc.filterReadResponse(scoped(), "facet_count", args("{}"), result);
        assertThat(filtered.get("realm")).hasSize(1);
        assertThat(filtered.get("realm").get(0).get("value").asText()).isEqualTo("dracul-research");
        assertThat(filtered.get("signal")).hasSize(1); // signal buckets untouched
    }
    @Test void facetCountForeignWhereRealmInDenied() throws Exception {
        assertThat(svc.realmDenial(scoped(), "facet_count",
                args("{\"fields\":[\"realm\"],\"where\":{\"realm_in\":[\"personal\"]}}"))).isPresent();
    }
    @Test void facetCountVisibleWhereRealmInAllowed() throws Exception {
        assertThat(svc.realmDenial(scoped(), "facet_count",
                args("{\"fields\":[\"realm\"],\"where\":{\"realm_in\":[\"dracul\"]}}"))).isEmpty();
    }

    // ---- C1: list_cell_ids row filter + precheck ----
    @Test void listCellIdsDropsForeignAndNullRealmRows() throws Exception {
        JsonNode result = mapper.readTree(
                "{\"ids\":[{\"id\":\"1\",\"realm\":\"dracul\"},"
                + "{\"id\":\"2\",\"realm\":\"personal\"},"
                + "{\"id\":\"3\",\"realm\":null}],\"total\":3}");
        JsonNode filtered = svc.filterReadResponse(scoped(), "list_cell_ids", args("{}"), result);
        assertThat(filtered.get("ids")).hasSize(1);
        assertThat(filtered.get("ids").get(0).get("realm").asText()).isEqualTo("dracul");
    }
    @Test void listCellIdsForeignWhereRealmDenied() throws Exception {
        assertThat(svc.realmDenial(scoped(), "list_cell_ids",
                args("{\"where\":{\"realm\":\"personal\"}}"))).isPresent();
    }

    // ---- I2 + C1: rewriteReadArgs injects read_realms ----
    @Test void rewriteInjectsReadRealmsForSearchWithoutRealm() throws Exception {
        JsonNode out = svc.rewriteReadArgs(scoped(), "search", args("{\"query\":\"x\"}"));
        JsonNode realmIn = out.path("where").path("realm_in");
        assertThat(realmIn.isArray()).isTrue();
        java.util.List<String> values = new java.util.ArrayList<>();
        realmIn.forEach(n -> values.add(n.asText()));
        assertThat(values).containsExactlyInAnyOrder("dracul-research", "dracul");
        assertThat(out.path("query").asText()).isEqualTo("x"); // query stays top-level
    }
    @Test void rewriteFoldsFlatFilterParamsIntoWhere() throws Exception {
        JsonNode out = svc.rewriteReadArgs(scoped(), "search",
                args("{\"query\":\"x\",\"signal\":\"facts\"}"));
        assertThat(out.has("signal")).isFalse(); // flat param folded away
        assertThat(out.path("where").path("signal").asText()).isEqualTo("facts");
        assertThat(out.path("where").path("realm_in").isArray()).isTrue();
    }
    @Test void rewriteLeavesCallerNamedRealmUntouched() throws Exception {
        JsonNode in = args("{\"where\":{\"realm_in\":[\"dracul\"]}}");
        JsonNode out = svc.rewriteReadArgs(scoped(), "list_cell_ids", in);
        JsonNode realmIn = out.path("where").path("realm_in");
        assertThat(realmIn).hasSize(1);
        assertThat(realmIn.get(0).asText()).isEqualTo("dracul");
    }
    @Test void rewriteInjectsForListCellIdsWithoutWhere() throws Exception {
        JsonNode out = svc.rewriteReadArgs(scoped(), "list_cell_ids", args("{}"));
        assertThat(out.path("where").path("realm_in").isArray()).isTrue();
        assertThat(out.path("where").path("realm_in")).hasSize(2);
    }
    @Test void rewriteNoOpForListTool() throws Exception {
        JsonNode in = args("{}");
        assertThat(svc.rewriteReadArgs(scoped(), "list", in)).isEqualTo(in);
    }

    // ---- ONE-SIDED SCOPE (read-only-scoped and write-only-scoped) ----
    private AuthPrincipal readOnlyScoped() {
        return new AuthPrincipal("t", AuthRole.WRITER, null, List.of("dracul-research"), null);
    }
    private AuthPrincipal writeOnlyScoped() {
        return new AuthPrincipal("t", AuthRole.WRITER, null, null, List.of("dracul-research"));
    }

    @Test void readOnlyScopedReclassifyNotDenied_writesUnrestricted() throws Exception {
        assertThat(svc.realmDenial(readOnlyScoped(), "reclassify", args("{\"realm\":\"dracul-research\"}"))).isEmpty();
    }
    @Test void readOnlyScopedAddCellForeignRealmNotDenied_writesUnrestricted() throws Exception {
        assertThat(svc.realmDenial(readOnlyScoped(), "add_cell", args("{\"realm\":\"personal\"}"))).isEmpty();
    }
    @Test void readOnlyScopedReviseCellNotDenied_writesUnrestricted() throws Exception {
        assertThat(svc.realmDenial(readOnlyScoped(), "revise_cell", args("{}"))).isEmpty();
    }
    @Test void readOnlyScopedTraverseStillDenied_readsAreFiltered() throws Exception {
        assertThat(svc.realmDenial(readOnlyScoped(), "traverse", args("{}"))).isPresent();
    }
    @Test void readOnlyScopedSearchResponseDropsForeignRows_readsAreFiltered() throws Exception {
        JsonNode result = mapper.readTree(
                "[{\"id\":\"1\",\"realm\":\"dracul-research\"},{\"id\":\"2\",\"realm\":\"personal\"}]");
        JsonNode filtered = svc.filterReadResponse(readOnlyScoped(), "search", args("{}"), result);
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).get("realm").asText()).isEqualTo("dracul-research");
    }

    @Test void writeOnlyScopedAddCellForeignRealmDenied() throws Exception {
        assertThat(svc.realmDenial(writeOnlyScoped(), "add_cell", args("{\"realm\":\"personal\"}"))).isPresent();
    }
    @Test void writeOnlyScopedTraverseNotDenied_readsAreUnrestricted() throws Exception {
        assertThat(svc.realmDenial(writeOnlyScoped(), "traverse", args("{}"))).isEmpty();
    }
    @Test void writeOnlyScopedSearchResponseUnchanged_readsAreUnrestricted() throws Exception {
        JsonNode result = mapper.readTree("[{\"id\":\"2\",\"realm\":\"personal\"}]");
        assertThat(svc.filterReadResponse(writeOnlyScoped(), "search", args("{}"), result)).isEqualTo(result);
    }

    // ---- H1: stringified `where` (MCP bridges stringify object args) ----
    // Production regression (Dracul strigoi-index, run 8C9A4F0B8EFC4C159626538ED6C5FC91): the
    // realm rewrite ran before the handler's string coercion and replaced the caller's textual
    // `where` with a fresh {realm_in:[...]} node, so every filter silently vanished and two
    // different symbol filters returned the byte-identical newest-rows page.

    @Test void rewriteParsesStringifiedWhereAndKeepsCallerKeys() throws Exception {
        JsonNode out = svc.rewriteReadArgs(scoped(), "search",
                args("{\"limit\":\"5\",\"where\":\"{\\\"realm\\\": \\\"dracul-research\\\", \\\"topic\\\": \\\"EA\\\"}\"}"));
        assertThat(out.path("where").isObject()).isTrue(); // normalized for every where-consumer
        assertThat(out.path("where").path("realm").asText()).isEqualTo("dracul-research");
        assertThat(out.path("where").path("topic").asText()).isEqualTo("EA");
        assertThat(out.path("limit").asText()).isEqualTo("5"); // untouched, handler coerces it
        // caller pinned a visible realm -> no realm_in injection, DB already restricts
        assertThat(out.path("where").has("realm_in")).isFalse();
    }

    @Test void rewriteParsesStringifiedWhereWithoutRealmAndInjectsRealmIn() throws Exception {
        JsonNode out = svc.rewriteReadArgs(scoped(), "search",
                args("{\"where\":\"{\\\"topic\\\": \\\"EA\\\"}\"}"));
        assertThat(out.path("where").path("topic").asText()).isEqualTo("EA"); // caller key survives
        assertThat(out.path("where").path("realm_in")).hasSize(2);            // scope still applied
    }

    @Test void stringifiedWhereForeignRealmDenied() throws Exception {
        // The precheck must see through the stringification too — otherwise the rewrite would
        // now leave a caller-pinned foreign realm untouched and unfiltered at the DB level.
        assertThat(svc.realmDenial(scoped(), "search",
                args("{\"where\":\"{\\\"realm\\\": \\\"personal\\\"}\"}"))).isPresent();
    }

    @Test void stringifiedWhereForeignRealmInDenied() throws Exception {
        assertThat(svc.realmDenial(scoped(), "search",
                args("{\"where\":\"{\\\"realm_in\\\": [\\\"personal\\\"]}\"}"))).isPresent();
    }

    @Test void stringifiedWhereVisibleRealmAllowed() throws Exception {
        assertThat(svc.realmDenial(scoped(), "search",
                args("{\"where\":\"{\\\"realm\\\": \\\"dracul\\\"}\"}"))).isEmpty();
    }

    // Malformed textual where: left verbatim, never replaced by a fresh scoped node. The handler
    // rejects it loudly ("Invalid where" / "where must be an object"); the scope is not widened
    // because no rows are ever fetched, and filterReadResponse remains the backstop.
    @Test void malformedStringifiedWhereLeftUntouchedNotSwallowed() throws Exception {
        JsonNode out = svc.rewriteReadArgs(scoped(), "search", args("{\"where\":\"not json at all\"}"));
        assertThat(out.path("where").isTextual()).isTrue();
        assertThat(out.path("where").asText()).isEqualTo("not json at all");
        assertThat(out.path("where").has("realm_in")).isFalse();
    }
    @Test void stringifiedNonObjectWhereLeftUntouched() throws Exception {
        JsonNode out = svc.rewriteReadArgs(scoped(), "search", args("{\"where\":\"[1,2]\"}"));
        assertThat(out.path("where").isTextual()).isTrue();
        assertThat(out.path("where").asText()).isEqualTo("[1,2]");
        assertThat(out.has("realm_in")).isFalse();
    }
    @Test void malformedStringifiedWhereNotDenied_handlerRejectsIt() throws Exception {
        assertThat(svc.realmDenial(scoped(), "search", args("{\"where\":\"not json\"}"))).isEmpty();
    }

    @Test void rewriteParsesStringifiedWhereForListCellIds() throws Exception {
        JsonNode out = svc.rewriteReadArgs(scoped(), "list_cell_ids",
                args("{\"where\":\"{\\\"signal\\\": \\\"facts\\\"}\"}"));
        assertThat(out.path("where").path("signal").asText()).isEqualTo("facts");
        assertThat(out.path("where").path("realm_in")).hasSize(2);
    }

    // ---- BACKWARD COMPAT (NULL/NULL = no-op) ----
    @Test void unscopedNeverDenied() throws Exception {
        for (String t : List.of("add_cell","reclassify","traverse","data_quality_report","list")) {
            assertThat(svc.realmDenial(unscoped(), t, args("{\"realm\":\"personal\"}"))).isEmpty();
        }
    }
    @Test void unscopedResponseUnchanged() throws Exception {
        JsonNode result = mapper.readTree("[{\"id\":\"2\",\"realm\":\"personal\"}]");
        assertThat(svc.filterReadResponse(unscoped(), "search", args("{}"), result)).isEqualTo(result);
    }
    @Test void unscopedArgsUnchanged() throws Exception {
        JsonNode a = args("{\"query\":\"x\"}");
        assertThat(svc.rewriteReadArgs(unscoped(), "search", a)).isEqualTo(a);
    }

    // ---- H1: a stringified JSON null `where` is "no filter", not a malformed one ----
    // Passing it through verbatim made the handlers read it as "no where" and query the DB with
    // realm=null AND realm_in=null: an unscoped cross-realm scan for a realm-scoped token.

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "search, null", "search, '  null  '",
            "facet_count, null", "list_cell_ids, null"})
    void stringifiedJsonNullWhereGetsRealmInInjected(String tool, String where) throws Exception {
        JsonNode out = svc.rewriteReadArgs(scoped(), tool, args("{\"where\":\"" + where + "\"}"));
        assertThat(out.path("where").isObject()).isTrue();
        assertThat(out.path("where").path("realm_in")).hasSize(2);
    }

    /** An empty `where` string is a missing node, not a JSON null: it stays a loud handler error. */
    @Test void emptyStringifiedWhereStaysUntouched() throws Exception {
        JsonNode out = svc.rewriteReadArgs(scoped(), "search", args("{\"where\":\"\"}"));
        assertThat(out.path("where").isTextual()).isTrue();
        assertThat(out.path("where").asText()).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"5", "true", "[1,2]", "not json at all"})
    void stringifiedNonObjectNonNullWhereStaysUntouchedForEveryInjectedTool(String where) throws Exception {
        for (String tool : java.util.List.of("search", "facet_count", "list_cell_ids")) {
            JsonNode out = svc.rewriteReadArgs(scoped(), tool, args("{\"where\":\"" + where + "\"}"));
            assertThat(out.path("where").isTextual()).as(tool).isTrue();
            assertThat(out.path("where").asText()).as(tool).isEqualTo(where);
        }
    }

    // ---- H2: list_cell_ids reads only `where`, so a flat realm must be folded into it ----
    // Unfolded, the "caller pinned a realm" shortcut handed the DB an unrestricted selector whose
    // `total` leaked the global cell count past filterReadResponse.

    @Test void listCellIdsFlatRealmIsFoldedIntoWhere() throws Exception {
        JsonNode out = svc.rewriteReadArgs(scoped(), "list_cell_ids", args("{\"realm\":\"dracul\"}"));
        assertThat(out.has("realm")).isFalse();
        assertThat(out.path("where").path("realm").asText()).isEqualTo("dracul");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"search", "facet_count", "list_cell_ids"})
    void scopedTokenAlwaysCarriesARealmPredicateIntoTheWhereObject(String tool) throws Exception {
        for (String argsJson : java.util.List.of(
                "{}", "{\"realm\":\"dracul\"}", "{\"where\":{\"realm\":\"dracul\"}}",
                "{\"where\":\"null\"}", "{\"where\":{\"signal\":\"facts\"}}")) {
            JsonNode out = svc.rewriteReadArgs(scoped(), tool, args(argsJson));
            JsonNode where = out.path("where");
            assertThat(where.hasNonNull("realm") || where.path("realm_in").isArray())
                    .as(tool + " " + argsJson)
                    .isTrue();
        }
    }
}
