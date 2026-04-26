package com.hivemem.sync;

import tools.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpLogBackfillRunnerTest {

    @Mock DSLContext dsl;
    @Mock OpLogWriter opLogWriter;
    @Mock Record countRecord;

    @Test
    void backfillPropagatesExceptionWhenTableFetchFails() {
        when(countRecord.get("c", Long.class)).thenReturn(0L);
        when(dsl.fetchOne("SELECT count(*) AS c FROM ops_log")).thenReturn(countRecord);
        when(dsl.fetch(anyString())).thenThrow(new RuntimeException("table cells does not exist"));

        var runner = new OpLogBackfillRunner(dsl, opLogWriter, new ObjectMapper());

        assertThatThrownBy(runner::runBackfill)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("table cells does not exist");
    }
}
