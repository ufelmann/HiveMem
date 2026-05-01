package com.hivemem.summarize;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class SummarizeBudgetTrackerIT {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    private DSLContext dsl;

    @BeforeEach
    void setUp() {
        org.flywaydb.core.Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .locations("classpath:db/migration").load().migrate();
        DataSource ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        dsl.execute("DELETE FROM summarize_usage");
    }

    @Test
    void canSpend_whenNoUsageYet() {
        SummarizeBudgetTracker t = new SummarizeBudgetTracker(dsl, 1.00);
        assertTrue(t.canSpend());
    }

    @Test
    void canSpend_underBudget() {
        SummarizeBudgetTracker t = new SummarizeBudgetTracker(dsl, 1.00);
        t.recordCall(1000, 200);                  // ~$0.0016
        assertTrue(t.canSpend());
    }

    @Test
    void cannotSpend_overBudget() {
        SummarizeBudgetTracker t = new SummarizeBudgetTracker(dsl, 0.001);  // tiny budget
        t.recordCall(1000, 200);
        assertFalse(t.canSpend());
    }

    @Test
    void recordCallUpserts() {
        SummarizeBudgetTracker t = new SummarizeBudgetTracker(dsl, 100.00);
        t.recordCall(500, 100);
        t.recordCall(500, 100);
        Long calls = ((Number) dsl.fetchOne("SELECT total_calls FROM summarize_usage").get(0)).longValue();
        assertEquals(2L, calls.longValue());
    }
}
