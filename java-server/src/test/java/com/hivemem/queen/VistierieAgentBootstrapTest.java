package com.hivemem.queen;

import com.hivemem.contradiction.ContradictionProperties;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class VistierieAgentBootstrapTest {

    private QueenProperties enabledProps() {
        QueenProperties p = new QueenProperties();
        p.setEnabled(true);
        p.setWebhookToken("wt");
        p.setCompletionWebhookToken("cwt");
        return p;
    }

    private ContradictionProperties contradictionProps(boolean enabled) {
        ContradictionProperties p = new ContradictionProperties();
        p.setEnabled(enabled);
        return p;
    }

    @Test
    void registersBeeBeforeQueen() {
        VistierieAgentClient client = mock(VistierieAgentClient.class);
        new VistierieAgentBootstrap(enabledProps(), contradictionProps(false), client).run(null);
        InOrder order = inOrder(client);
        order.verify(client).upsertAgent(eq("isolated-cell-bee"), any());
        order.verify(client).upsertAgent(eq("queen"), any());
    }

    @Test
    void disabledDoesNothing() {
        VistierieAgentClient client = mock(VistierieAgentClient.class);
        QueenProperties disabled = new QueenProperties(); // enabled=false
        new VistierieAgentBootstrap(disabled, contradictionProps(false), client).run(null);
        verifyNoInteractions(client);
    }

    @Test
    void registrationFailureDoesNotThrow() {
        VistierieAgentClient client = mock(VistierieAgentClient.class);
        doThrow(new RuntimeException("connect refused"))
                .when(client).upsertAgent(eq("isolated-cell-bee"), any());
        // must not throw
        new VistierieAgentBootstrap(enabledProps(), contradictionProps(false), client).run(null);
        // queen never attempted because bee failed; bootstrap swallowed the error
        verify(client, never()).upsertAgent(eq("queen"), any());
    }

    /**
     * Today's actual production state: queen on, contradiction off, no completion webhook token
     * configured for the judges. The two judge agents must not be registered — registering them
     * unconditionally would upsert agents carrying a blank {@code completion_webhook_token} on
     * every single boot.
     */
    @Test
    void judgeAgentsAreNotRegisteredWhenContradictionIsDisabled() {
        VistierieAgentClient client = mock(VistierieAgentClient.class);
        new VistierieAgentBootstrap(enabledProps(), contradictionProps(false), client).run(null);

        verify(client, never()).upsertAgent(eq("contradiction-judge"), any());
        verify(client, never()).upsertAgent(eq("predicate-cardinality-judge"), any());
        // the always-on agents still register
        verify(client).upsertAgent(eq("isolated-cell-bee"), any());
        verify(client).upsertAgent(eq("queen"), any());
    }

    @Test
    void judgeAgentsAreRegisteredWhenContradictionIsEnabled() {
        VistierieAgentClient client = mock(VistierieAgentClient.class);
        new VistierieAgentBootstrap(enabledProps(), contradictionProps(true), client).run(null);

        verify(client).upsertAgent(eq("contradiction-judge"), any());
        verify(client).upsertAgent(eq("predicate-cardinality-judge"), any());
    }
}
