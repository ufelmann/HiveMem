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
 *
 * <p>The Queen being on is not sufficient by itself: {@code
 * VistierieWebhookController.requireToken()} also rejects every callback with 401 while its
 * expected token is blank (the property's default). {@code
 * hivemem.queen.contradiction-webhook-token} is exactly that token for both contradiction
 * webhooks ({@code /vistierie/contradiction/done}, {@code /vistierie/cardinality/done}), so this
 * gate requires it to be set too — otherwise contradiction detection boots cleanly, dispatches
 * real Vistierie runs, and rejects every completion callback with 401, grinding the whole backlog
 * through stale-fail → retryable → {@code deferred} once {@code maxAttempts} is hit.
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
        verify(props.isEnabled(), queenProps.isEnabled(), queenProps.getContradictionWebhookToken());
    }

    static void verify(boolean contradictionEnabled, boolean queenEnabled, String contradictionWebhookToken) {
        if (!contradictionEnabled) {
            return;
        }
        if (!queenEnabled) {
            throw new IllegalStateException(
                    "hivemem.contradiction.enabled=true requires hivemem.queen.enabled=true: "
                            + "the judge agents are registered by the Queen bootstrap and the "
                            + "completion webhooks are rejected with 401 while the Queen is off.");
        }
        if (contradictionWebhookToken == null || contradictionWebhookToken.isBlank()) {
            throw new IllegalStateException(
                    "hivemem.contradiction.enabled=true requires a non-blank "
                            + "hivemem.queen.contradiction-webhook-token: while it is blank, "
                            + "VistierieWebhookController rejects every contradiction/cardinality "
                            + "completion callback with 401, so dispatched runs can never complete.");
        }
    }
}
