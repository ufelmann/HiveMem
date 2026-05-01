package com.hivemem.attachment;

import org.flywaydb.core.Flyway;
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
class VisionBudgetTrackerIT {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    private DSLContext dsl;

    @BeforeEach
    void setUp() {
        Flyway.configure().dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .locations("classpath:db/migration").load().migrate();
        DataSource ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        dsl.execute("DELETE FROM vision_usage");
    }

    @Test
    void canSpendWhenNoUsageToday() {
        VisionBudgetTracker t = new VisionBudgetTracker(dsl, 1.00);
        assertTrue(t.canSpend());
    }

    @Test
    void canSpendBlocksWhenBudgetExhausted() {
        VisionBudgetTracker t = new VisionBudgetTracker(dsl, 0.001);
        t.recordCall(2_000_000, 0); // ~$2 input cost
        assertFalse(t.canSpend());
    }

    @Test
    void recordCallUpserts() {
        VisionBudgetTracker t = new VisionBudgetTracker(dsl, 100);
        t.recordCall(1_000_000, 100_000);
        t.recordCall(500_000, 50_000);
        var row = dsl.fetchOne("SELECT total_calls, total_input_tokens, total_output_tokens FROM vision_usage");
        assertEquals(2, row.get("total_calls", Integer.class));
        assertEquals(1_500_000, row.get("total_input_tokens", Integer.class));
        assertEquals(150_000, row.get("total_output_tokens", Integer.class));
    }

    @Test
    void zeroBudgetBlocks() {
        VisionBudgetTracker t = new VisionBudgetTracker(dsl, 0.0);
        assertFalse(t.canSpend());
    }
}
