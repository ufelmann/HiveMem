package com.hivemem.contradiction;

import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContradictionSchemaIT extends ContradictionITSupport {

    @Autowired DSLContext dsl;

    /** The jobs table must accept 'processing' — it mirrors consumption_jobs, whose claim() writes it. */
    @Test
    void jobsStatusAcceptsProcessing() {
        UUID correlationId = UUID.randomUUID();
        dsl.execute("INSERT INTO contradiction_jobs (correlation_id, kind, item_count) "
                + "VALUES (?, 'pairs', 3)", correlationId);
        int updated = dsl.execute(
                "UPDATE contradiction_jobs SET status='processing' WHERE correlation_id=?", correlationId);
        assertThat(updated).isEqualTo(1);
    }

    /** The pair index is order-independent: (A,B) and (B,A) are one pair. */
    @Test
    void pairIndexIsOrderIndependent() {
        UUID a = insertFact("s-pair", "p", "x");
        UUID b = insertFact("s-pair", "p", "y");
        dsl.execute("INSERT INTO fact_contradictions (fact_a, fact_b, subject, predicate) "
                + "VALUES (?, ?, 's-pair', 'p')", a, b);
        assertThatThrownBy(() -> dsl.execute(
                "INSERT INTO fact_contradictions (fact_a, fact_b, subject, predicate) "
                        + "VALUES (?, ?, 's-pair', 'p')", b, a))
                .hasMessageContaining("ux_fact_contradictions_pair");
    }

    /** A fact cannot contradict itself — the pair index alone would accept (A,A). */
    @Test
    void selfPairIsRejected() {
        UUID a = insertFact("s-self", "p", "x");
        assertThatThrownBy(() -> dsl.execute(
                "INSERT INTO fact_contradictions (fact_a, fact_b, subject, predicate) "
                        + "VALUES (?, ?, 's-self', 'p')", a, a))
                .hasMessageContaining("ck_fact_contradictions_distinct");
    }

    /** cardinality is nullable: a row exists from reservation, before any verdict. */
    @Test
    void cardinalityIsNullableUntilDecided() {
        dsl.execute("INSERT INTO predicate_cardinality (predicate) VALUES ('reserved_only')");
        Record reserved = dsl.fetchOne(
                "SELECT status, cardinality FROM predicate_cardinality WHERE predicate='reserved_only'");
        assertThat(reserved.get("status", String.class)).isEqualTo("in_flight");
        assertThat(reserved.get("cardinality", String.class)).isNull();
    }
}
