package com.hivemem.contradiction;

import com.hivemem.queen.QueenProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Both halves of the Vistierie integration are gated on hivemem.queen.enabled:
 * VistierieAgentBootstrap skips agent registration when it is off, and
 * VistierieWebhookController.requireToken() returns 401 for every callback.
 * Enabling only the contradiction flag would dispatch to an unregistered agent and
 * reject the callbacks, quietly burning quota on jobs that can never complete.
 */
@Component
public class ContradictionStartupGate implements ApplicationRunner {

    private final ContradictionProperties props;
    private final QueenProperties queenProps;

    public ContradictionStartupGate(ContradictionProperties props, QueenProperties queenProps) {
        this.props = props;
        this.queenProps = queenProps;
    }

    @Override
    public void run(ApplicationArguments args) {
        verify(props.isEnabled(), queenProps.isEnabled());
    }

    static void verify(boolean contradictionEnabled, boolean queenEnabled) {
        if (contradictionEnabled && !queenEnabled) {
            throw new IllegalStateException(
                    "hivemem.contradiction.enabled=true requires hivemem.queen.enabled=true: "
                            + "the judge agents are registered by the Queen bootstrap and the "
                            + "completion webhooks are rejected with 401 while the Queen is off.");
        }
    }
}
