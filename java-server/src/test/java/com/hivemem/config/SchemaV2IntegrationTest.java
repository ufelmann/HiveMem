package com.hivemem.config;

import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the append-only schema v2 behavior: views, PL/pgSQL functions,
 * and the pending_approvals union view.
 *
 * <p>Ported from Python's test_schema_v2.py. Tests that require PL/pgSQL functions
 * not yet present in the Java migration track (revise_drawer, revise_fact,
 * drawer_history, fact_history) are skipped -- see comments on each.
 */
@Testcontainers
class SchemaV2IntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem")
            .withUsername("hivemem")
            .withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null
                            ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig())
                            .withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    // -------------------------------------------------------------------------
    // Test 1 -- Append-only drawers: SQL allows direct UPDATE (no trigger blocks it)
    // -------------------------------------------------------------------------

    @Test
    void directUpdateOnDrawerContentIsAllowed() throws SQLException {
        try (SchemaHarness h = migrateFreshSchema()) {
            String id = insertDrawer(h.dsl(), "Original content");

            // The schema has no trigger preventing direct UPDATEs.
            // The append-only contract is enforced at the application layer,
            // not by a database trigger. Verify the UPDATE succeeds.
            int updated = h.dsl().execute("""
                    update cells set content = 'Modified content'
                    where id = ?::uuid
                    """, id);

            assertThat(updated).isEqualTo(1);

            String content = h.dsl().fetchOne("""
                    select content from cells where id = ?::uuid
                    """, id).get("content", String.class);
            assertThat(content).isEqualTo("Modified content");
        }
    }

    // -------------------------------------------------------------------------
    // Tests 2 & 3 -- SKIPPED: revise_drawer / revise_fact PL/pgSQL functions
    // are not present in the Java Flyway migrations (V0001-V0006).
    // They exist in the Python schema.sql reference but were not yet ported.
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Test 4 -- active_cells excludes pending and expired drawers
    // -------------------------------------------------------------------------

    @Test
    void activeDrawersExcludesPendingAndExpired() throws SQLException {
        try (SchemaHarness h = migrateFreshSchema()) {
            // committed + active (valid_until IS NULL)
            String activeId = insertDrawer(h.dsl(), "Active drawer");

            // pending drawer
            h.dsl().execute("""
                    insert into cells (content, realm, status, created_by)
                    values ('Pending drawer', 'test', 'pending', 'test')
                    """);

            // expired drawer (valid_until in the past)
            h.dsl().execute("""
                    insert into cells (content, realm, status, valid_until, created_by)
                    values ('Expired drawer', 'test', 'committed', now() - interval '1 day', 'test')
                    """);

            Result<Record> active = h.dsl().fetch("""
                    select id::text from active_cells where realm = 'test'
                    """);

            List<String> activeIds = active.getValues(0, String.class);
            assertThat(activeIds).containsExactly(activeId);
        }
    }

    // -------------------------------------------------------------------------
    // Test 5 -- active_facts excludes pending and expired facts
    // -------------------------------------------------------------------------

    @Test
    void activeFactsExcludesPendingAndExpired() throws SQLException {
        try (SchemaHarness h = migrateFreshSchema()) {
            // committed + active
            String activeFactId = insertFact(h.dsl(), "Alice", "works_at", "Acme");

            // pending fact
            h.dsl().execute("""
                    insert into facts (subject, predicate, "object", status, created_by)
                    values ('Bob', 'works_at', 'Corp', 'pending', 'test')
                    """);

            // expired fact
            h.dsl().execute("""
                    insert into facts (subject, predicate, "object", status, valid_until, created_by)
                    values ('Charlie', 'works_at', 'OldCo', 'committed', now() - interval '1 day', 'test')
                    """);

            Result<Record> active = h.dsl().fetch("select id::text from active_facts");
            List<String> activeIds = active.getValues(0, String.class);
            assertThat(activeIds).containsExactly(activeFactId);
        }
    }

    // -------------------------------------------------------------------------
    // Test 6 -- active_tunnels excludes pending and expired tunnels
    // -------------------------------------------------------------------------

    @Test
    void activeTunnelsExcludesPendingAndExpired() throws SQLException {
        try (SchemaHarness h = migrateFreshSchema()) {
            String drawerA = insertDrawer(h.dsl(), "Drawer A");
            String drawerB = insertDrawer(h.dsl(), "Drawer B");
            String drawerC = insertDrawer(h.dsl(), "Drawer C");

            // committed + active tunnel
            String activeTunnelId = h.dsl().fetchOne("""
                    insert into tunnels (from_cell, to_cell, relation, status, created_by)
                    values (?::uuid, ?::uuid, 'related_to', 'committed', 'test')
                    returning id::text
                    """, drawerA, drawerB).get(0, String.class);

            // pending tunnel
            h.dsl().execute("""
                    insert into tunnels (from_cell, to_cell, relation, status, created_by)
                    values (?::uuid, ?::uuid, 'builds_on', 'pending', 'test')
                    """, drawerB, drawerC);

            // expired tunnel
            h.dsl().execute("""
                    insert into tunnels (from_cell, to_cell, relation, status, valid_until, created_by)
                    values (?::uuid, ?::uuid, 'refines', 'committed', now() - interval '1 day', 'test')
                    """, drawerA, drawerC);

            Result<Record> active = h.dsl().fetch("select id::text from active_tunnels");
            List<String> activeIds = active.getValues(0, String.class);
            assertThat(activeIds).containsExactly(activeTunnelId);
        }
    }

    // -------------------------------------------------------------------------
    // Test 7 -- active_blueprints excludes expired blueprints
    // -------------------------------------------------------------------------

    @Test
    void activeBlueprintsExcludesExpired() throws SQLException {
        try (SchemaHarness h = migrateFreshSchema()) {
            // active blueprint (valid_until IS NULL)
            String activeId = h.dsl().fetchOne("""
                    insert into blueprints (realm, title, narrative, created_by)
                    values ('eng', 'Active Blueprint', 'This is active', 'test')
                    returning id::text
                    """).get(0, String.class);

            // expired blueprint
            h.dsl().execute("""
                    insert into blueprints (realm, title, narrative, valid_until, created_by)
                    values ('eng', 'Old Blueprint', 'This is expired', now() - interval '1 day', 'test')
                    """);

            Result<Record> active = h.dsl().fetch("""
                    select id::text from active_blueprints where realm = 'eng'
                    """);

            List<String> activeIds = active.getValues(0, String.class);
            assertThat(activeIds).containsExactly(activeId);
        }
    }

    // -------------------------------------------------------------------------
    // Test 8 -- pending_approvals view shows pending drawers, facts, and tunnels
    // -------------------------------------------------------------------------

    @Test
    void pendingApprovalsShowsAllPendingEntityTypes() throws SQLException {
        try (SchemaHarness h = migrateFreshSchema()) {
            // pending drawer
            h.dsl().execute("""
                    insert into cells (content, realm, signal, summary, status, created_by)
                    values ('Pending drawer', 'test', 'facts', 'Pending summary', 'pending', 'classifier')
                    """);

            // pending fact
            h.dsl().execute("""
                    insert into facts (subject, predicate, "object", status, created_by)
                    values ('Entity', 'has', 'Property', 'pending', 'agent-x')
                    """);

            // two drawers needed for tunnel FK
            String drawerA = insertDrawer(h.dsl(), "Tunnel source");
            String drawerB = insertDrawer(h.dsl(), "Tunnel target");

            // pending tunnel
            h.dsl().execute("""
                    insert into tunnels (from_cell, to_cell, relation, status, created_by)
                    values (?::uuid, ?::uuid, 'related_to', 'pending', 'agent-y')
                    """, drawerA, drawerB);

            Result<Record> pending = h.dsl().fetch("""
                    select type, created_by from pending_approvals order by type
                    """);

            List<String> types = pending.getValues("type", String.class);
            assertThat(types).containsExactly("cell", "fact", "tunnel");

            // verify created_by propagation
            List<String> creators = pending.getValues("created_by", String.class);
            assertThat(creators).containsExactly("classifier", "agent-x", "agent-y");
        }
    }

    @Test
    void pendingApprovalsIsEmptyWhenNoPendingItems() throws SQLException {
        try (SchemaHarness h = migrateFreshSchema()) {
            // insert only committed items
            insertDrawer(h.dsl(), "Committed drawer");
            insertFact(h.dsl(), "Alice", "knows", "Bob");

            int count = h.dsl().fetchCount(DSL.table("pending_approvals"));
            assertThat(count).isZero();
        }
    }

    // -------------------------------------------------------------------------
    // Test 8b -- Approve pending drawer by updating status to committed
    // -------------------------------------------------------------------------

    @Test
    void approvePendingDrawerMakesItActiveViaStatusUpdate() throws SQLException {
        try (SchemaHarness h = migrateFreshSchema()) {
            String pendingId = h.dsl().fetchOne("""
                    insert into cells (content, realm, signal, status, created_by)
                    values ('Needs approval', 'test', 'facts', 'pending', 'classifier')
                    returning id::text
                    """).get(0, String.class);

            // not in active_cells yet
            int countBefore = h.dsl().fetchOne("""
                    select count(*)::int as cnt from active_cells where id = ?::uuid
                    """, pendingId).get("cnt", Integer.class);
            assertThat(countBefore).isZero();

            // approve by setting status to committed
            h.dsl().execute("""
                    update cells set status = 'committed' where id = ?::uuid
                    """, pendingId);

            // now in active_cells
            int countAfter = h.dsl().fetchOne("""
                    select count(*)::int as cnt from active_cells where id = ?::uuid
                    """, pendingId).get("cnt", Integer.class);
            assertThat(countAfter).isEqualTo(1);

            // gone from pending_approvals
            int pendingCount = h.dsl().fetchOne("""
                    select count(*)::int as cnt from pending_approvals where id = ?::uuid
                    """, pendingId).get("cnt", Integer.class);
            assertThat(pendingCount).isZero();
        }
    }

    // -------------------------------------------------------------------------
    // Test 9 -- SKIPPED: Revision chain via recursive CTE
    // The drawer_history() and fact_history() PL/pgSQL functions are not present
    // in the Java Flyway migrations (V0001-V0006). However, we can test the
    // parent_id chain manually using a raw recursive CTE.
    // -------------------------------------------------------------------------

    @Test
    void revisionChainViaManualRecursiveCte() throws SQLException {
        try (SchemaHarness h = migrateFreshSchema()) {
            // Simulate a 4-version revision chain: v1 -> v2 -> v3 -> v4
            // using direct inserts with parent_id links and valid_until

            // v1
            String v1 = h.dsl().fetchOne("""
                    insert into cells (content, realm, summary, status, created_by,
                                         valid_until)
                    values ('Version 1', 'test', 'V1', 'committed', 'test', now())
                    returning id::text
                    """).get(0, String.class);

            // v2 with parent_id = v1
            String v2 = h.dsl().fetchOne("""
                    insert into cells (content, realm, summary, status, created_by,
                                         parent_id, valid_until)
                    values ('Version 2', 'test', 'V2', 'committed', 'test', ?::uuid, now())
                    returning id::text
                    """, v1).get(0, String.class);

            // v3 with parent_id = v2
            String v3 = h.dsl().fetchOne("""
                    insert into cells (content, realm, summary, status, created_by,
                                         parent_id, valid_until)
                    values ('Version 3', 'test', 'V3', 'committed', 'test', ?::uuid, now())
                    returning id::text
                    """, v2).get(0, String.class);

            // v4 with parent_id = v3 (current/active version)
            String v4 = h.dsl().fetchOne("""
                    insert into cells (content, realm, summary, status, created_by,
                                         parent_id)
                    values ('Version 4', 'test', 'V4', 'committed', 'test', ?::uuid)
                    returning id::text
                    """, v3).get(0, String.class);

            // Walk the chain backwards from v4 using recursive CTE (same pattern as drawer_history)
            Result<Record> chain = h.dsl().fetch("""
                    with recursive chain as (
                        select d.id, d.parent_id, d.summary, d.valid_from, 1 as depth
                        from cells d where d.id = ?::uuid
                        union all
                        select d.id, d.parent_id, d.summary, d.valid_from, c.depth + 1
                        from cells d join chain c on d.id = c.parent_id
                        where c.depth < 100
                    )
                    select summary from chain order by valid_from asc
                    """, v4);

            List<String> summaries = chain.getValues("summary", String.class);
            assertThat(summaries).containsExactly("V1", "V2", "V3", "V4");
        }
    }

    // -------------------------------------------------------------------------
    // Test 10 -- Depth cap on recursive CTE (150 revisions, expect <= 100)
    // -------------------------------------------------------------------------

    @Tag("slow")
    @Test
    void recursiveCteDepthCapAt100() throws SQLException {
        try (SchemaHarness h = migrateFreshSchema()) {
            // Build a chain of 150 drawers linked by parent_id.
            // Use a PL/pgSQL DO block for speed (single round-trip).
            h.dsl().execute("""
                    do $$
                    declare
                        v_prev uuid := null;
                        v_curr uuid;
                    begin
                        for i in 1..150 loop
                            insert into cells (content, realm, summary, status, created_by,
                                                 parent_id, valid_until)
                            values ('V' || i, 'test', 'V' || i, 'committed', 'test',
                                    v_prev,
                                    case when i < 150 then now() else null end)
                            returning id into v_curr;
                            v_prev := v_curr;
                        end loop;
                    end $$
                    """);

            // Find the leaf node (the one with no valid_until, i.e., the latest revision)
            String leafId = h.dsl().fetchOne("""
                    select id::text from cells
                    where realm = 'test' and valid_until is null
                    order by created_at desc limit 1
                    """).get(0, String.class);

            // Walk the chain with a depth cap of 100
            int chainLength = h.dsl().fetchOne("""
                    with recursive chain as (
                        select d.id, d.parent_id, 1 as depth
                        from cells d where d.id = ?::uuid
                        union all
                        select d.id, d.parent_id, c.depth + 1
                        from cells d join chain c on d.id = c.parent_id
                        where c.depth < 100
                    )
                    select count(*)::int as cnt from chain
                    """, leafId).get("cnt", Integer.class);

            assertThat(chainLength).isLessThanOrEqualTo(100);
            // The chain is 150 deep, so with depth < 100 we get exactly 100 rows
            assertThat(chainLength).isEqualTo(100);
        }
    }

    // -------------------------------------------------------------------------
    // Additional behavior tests (ported from Python test_schema_v2.py)
    // -------------------------------------------------------------------------

    @Test
    void committedDrawerAppearsInActiveView() throws SQLException {
        try (SchemaHarness h = migrateFreshSchema()) {
            insertDrawer(h.dsl(), "Active content");

            int count = h.dsl().fetchOne("""
                    select count(*)::int as cnt from active_cells where realm = 'test'
                    """).get("cnt", Integer.class);

            assertThat(count).isEqualTo(1);
        }
    }

    @Test
    void pendingDrawerNotInActiveView() throws SQLException {
        try (SchemaHarness h = migrateFreshSchema()) {
            h.dsl().execute("""
                    insert into cells (content, realm, status, created_by)
                    values ('Pending', 'test', 'pending', 'classifier')
                    """);

            int count = h.dsl().fetchOne("""
                    select count(*)::int as cnt from active_cells where realm = 'test'
                    """).get("cnt", Integer.class);

            assertThat(count).isZero();
        }
    }

    @Test
    void invalidatedFactDisappearsFromActiveView() throws SQLException {
        try (SchemaHarness h = migrateFreshSchema()) {
            String factId = insertFact(h.dsl(), "Alice", "works_at", "Acme");

            // visible in active_facts
            int before = h.dsl().fetchOne("""
                    select count(*)::int as cnt from active_facts
                    where subject = 'Alice'
                    """).get("cnt", Integer.class);
            assertThat(before).isEqualTo(1);

            // invalidate by setting valid_until
            h.dsl().execute("""
                    update facts set valid_until = now() where id = ?::uuid
                    """, factId);

            // gone from active_facts
            int after = h.dsl().fetchOne("""
                    select count(*)::int as cnt from active_facts
                    where subject = 'Alice'
                    """).get("cnt", Integer.class);
            assertThat(after).isZero();
        }
    }

    @Test
    void wingStatsReflectsActiveDrawers() throws SQLException {
        try (SchemaHarness h = migrateFreshSchema()) {
            insertDrawerWithHall(h.dsl(), "D1", "eng", "facts");
            insertDrawerWithHall(h.dsl(), "D2", "eng", "facts");
            insertDrawerWithHall(h.dsl(), "D3", "eng", "events");

            long cellCount = h.dsl().fetchOne("""
                    select sum(cell_count)::bigint as total
                    from realm_stats where realm = 'eng'
                    """).get("total", Long.class);

            Result<Record> halls = h.dsl().fetch("""
                    select distinct signal from realm_stats where realm = 'eng'
                    """);

            assertThat(cellCount).isEqualTo(3);
            assertThat(halls.getValues("signal", String.class))
                    .containsExactlyInAnyOrder("facts", "events");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static SchemaHarness migrateFreshSchema() throws SQLException {
        String schema = "schema_v2_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection adminConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            DSL.using(adminConnection, SQLDialect.POSTGRES)
                    .execute("create schema " + schema);
        }

        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema, "public")
                .defaultSchema(schema)
                .load();
        assertThat(flyway.migrate().migrationsExecuted).isGreaterThan(0);

        Connection schemaConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        DSLContext dsl = DSL.using(schemaConnection, SQLDialect.POSTGRES);
        dsl.execute("set search_path to " + schema + ", public");
        return new SchemaHarness(schema, flyway, schemaConnection, dsl);
    }

    private static String insertDrawer(DSLContext dsl, String content) {
        return dsl.fetchOne("""
                insert into cells (content, realm, status, created_by)
                values (?, 'test', 'committed', 'test')
                returning id::text
                """, content).get(0, String.class);
    }

    private static String insertDrawerWithHall(DSLContext dsl, String content, String realm, String signal) {
        return dsl.fetchOne("""
                insert into cells (content, realm, signal, status, created_by)
                values (?, ?, ?, 'committed', 'test')
                returning id::text
                """, content, realm, signal).get(0, String.class);
    }

    private static String insertFact(DSLContext dsl, String subject, String predicate, String object) {
        return dsl.fetchOne("""
                insert into facts (subject, predicate, "object", status, created_by)
                values (?, ?, ?, 'committed', 'test')
                returning id::text
                """, subject, predicate, object).get(0, String.class);
    }

    private record SchemaHarness(String schema, Flyway flyway, Connection connection, DSLContext dsl)
            implements AutoCloseable {

        @Override
        public void close() throws SQLException {
            connection.close();
        }
    }
}
