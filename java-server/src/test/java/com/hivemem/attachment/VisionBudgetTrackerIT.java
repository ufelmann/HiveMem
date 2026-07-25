package com.hivemem.attachment;

import com.hivemem.llm.LlmCallCost;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
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
        t.recordCall(call(2_000_000, 0, 2_000_000L)); // €2.00 reported by Vistierie
        assertFalse(t.canSpend());
    }

    /**
     * Every input kind Vistierie reports has to reach {@code total_input_tokens}: with prompt
     * caching on, the bulk of the real input sits in the two cache fields, so booking only
     * {@code inputTokens} under-counts by orders of magnitude. The booked EUR amount is
     * Vistierie's own {@code cost_micros}, not a locally recomputed price.
     */
    @Test
    void booksAllInputKindsAndTheVistierieCost() {
        VisionBudgetTracker t = new VisionBudgetTracker(dsl, 5.0);
        var call = new LlmCallCost("bedrock", "claude-haiku-4-5", 1500, 300, 4000, 21000, 2343L);

        assertThat(t.recordCall(call)).isEqualByComparingTo(new BigDecimal("0.002343"));

        var row = dsl.fetchOne(
                "SELECT total_input_tokens, total_output_tokens, total_cost_usd "
                        + "FROM vision_usage WHERE day = ?", LocalDate.now(ZoneOffset.UTC));
        assertThat(row.get("total_input_tokens", Integer.class)).isEqualTo(26500); // 1500+4000+21000
        assertThat(row.get("total_output_tokens", Integer.class)).isEqualTo(300);
        assertThat(row.get("total_cost_usd", BigDecimal.class))
                .isEqualByComparingTo(new BigDecimal("0.002343"));
    }

    @Test
    void recordCallAccumulatesTheDaysTotalsInsteadOfOverwritingThem() {
        // The whole point of the ON CONFLICT clause: a second call for the same day must ADD to
        // the row, not replace it. Every value below is deliberately distinct from every other
        // and from each sum, so a pure-overwrite upsert ("= EXCLUDED.x") cannot coincidentally
        // produce the expected numbers — if accumulation regresses, canSpend() would see only
        // the last call and the daily cap would be silently unbounded.
        VisionBudgetTracker t = new VisionBudgetTracker(dsl, 100);

        t.recordCall(call(1_000_000, 100_000, 800L));  // €0.000800
        // Second call: different everything, and cache tokens count as input too.
        t.recordCall(new LlmCallCost("bedrock", "claude-haiku-4-5", 7000, 30, 1200, 40, 2_500L));

        var row = dsl.fetchOne(
                "SELECT total_calls, total_input_tokens, total_output_tokens, total_cost_usd "
                        + "FROM vision_usage WHERE day = ?", LocalDate.now(ZoneOffset.UTC));
        assertThat(row.get("total_calls", Integer.class)).isEqualTo(2);
        assertThat(row.get("total_input_tokens", Integer.class))
                .isEqualTo(1_008_240);                                              // 1000000+7000+1200+40
        assertThat(row.get("total_output_tokens", Integer.class)).isEqualTo(100_030); // 100000+30
        assertThat(row.get("total_cost_usd", BigDecimal.class))                       // 0.000800+0.002500
                .isEqualByComparingTo(new BigDecimal("0.003300"));
    }

    private static LlmCallCost call(int in, int out, long costMicros) {
        return new LlmCallCost("bedrock", "claude-haiku-4-5", in, out, 0, 0, costMicros);
    }

    /**
     * A subscription-routed call is free but is still a call: it has to increment
     * {@code total_calls} and the token columns while adding exactly 0.00 to
     * {@code total_cost_usd}. Mirrors
     * {@code SummarizeBudgetTrackerIT.subscriptionCallCostsNothingButStillCounts}.
     */
    @Test
    void subscriptionCallCostsNothingButStillCounts() {
        VisionBudgetTracker t = new VisionBudgetTracker(dsl, 30.0);
        var c = new LlmCallCost("claude-subscription", "claude-sonnet-5", 2, 1487, 25681, 0, 0L);

        assertThat(t.recordCall(c)).isEqualByComparingTo(BigDecimal.ZERO);

        var row = dsl.fetchOne(
                "SELECT total_calls, total_input_tokens, total_output_tokens, total_cost_usd "
                        + "FROM vision_usage WHERE day = ?", LocalDate.now(ZoneOffset.UTC));
        assertThat(row.get("total_calls", Integer.class)).isEqualTo(1);
        assertThat(row.get("total_input_tokens", Integer.class)).isEqualTo(25683); // 2 + 25681 + 0
        assertThat(row.get("total_output_tokens", Integer.class)).isEqualTo(1487);
        assertThat(row.get("total_cost_usd", BigDecimal.class)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void zeroBudgetBlocks() {
        VisionBudgetTracker t = new VisionBudgetTracker(dsl, 0.0);
        assertFalse(t.canSpend());
    }
}
