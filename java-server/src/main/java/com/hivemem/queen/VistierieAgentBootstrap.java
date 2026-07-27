package com.hivemem.queen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * On startup (when hivemem.queen.enabled=true) idempotently registers the Bee then the
 * Queen in Vistierie. Tolerant: if Vistierie is unreachable, logs a warning and lets the
 * application continue — the next boot heals the registration.
 *
 * <p>The two contradiction-detection judges (contradiction-judge, predicate-cardinality-judge)
 * are additionally gated on {@code hivemem.contradiction.enabled}: both carry a {@code
 * completion_webhook_token} sourced from {@code hivemem.queen.contradiction-webhook-token},
 * which defaults to blank, and {@code com.hivemem.contradiction.ContradictionStartupGate} already
 * refuses to boot with contradiction enabled and that token blank — but with contradiction OFF
 * (today's production default) nothing enforces the token, so upserting these two agents
 * unconditionally on every queen-enabled install
 * would register them with an empty completion webhook token every single boot. Registering only
 * once the feature is actually switched on is the more correct default; nothing in this codebase
 * dispatches to either judge name outside {@code VistierieContradictionClient}/{@code
 * VistierieCardinalityClient}, both of which are themselves {@code @ConditionalOnProperty} on the
 * same flag, so no caller ever expects these two agents to exist while contradiction is off.
 */
@Component
public class VistierieAgentBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VistierieAgentBootstrap.class);

    private final QueenProperties props;
    private final com.hivemem.contradiction.ContradictionProperties contradictionProps;
    private final VistierieAgentClient client;

    public VistierieAgentBootstrap(QueenProperties props,
            com.hivemem.contradiction.ContradictionProperties contradictionProps,
            VistierieAgentClient client) {
        this.props = props;
        this.contradictionProps = contradictionProps;
        this.client = client;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isEnabled()) {
            log.info("Queen disabled (hivemem.queen.enabled=false) — skipping agent bootstrap");
            return;
        }
        AgentDefinitions defs = new AgentDefinitions(props);
        try {
            client.upsertAgent(AgentDefinitions.BEE_NAME, defs.isolatedCellBee());
            client.upsertAgent(AgentDefinitions.QUEEN_NAME, defs.queen());
            client.upsertAgent(AgentDefinitions.SEPARATOR_NAME, defs.documentSeparator());
            client.upsertAgent(AgentDefinitions.ARCHIVIST_NAME, defs.inboxArchivist());
            if (contradictionProps.isEnabled()) {
                client.upsertAgent(AgentDefinitions.CONTRADICTION_JUDGE_NAME, defs.contradictionJudge());
                client.upsertAgent(AgentDefinitions.CARDINALITY_JUDGE_NAME, defs.predicateCardinalityJudge());
                log.info("Registered Queen + Bee + Separator + Archivist + ContradictionJudge + "
                        + "CardinalityJudge agents in Vistierie at {}", props.getVistierieBaseUrl());
            } else {
                log.info("Registered Queen + Bee + Separator + Archivist agents in Vistierie at {} "
                        + "(contradiction detection disabled — judge agents not registered)",
                        props.getVistierieBaseUrl());
            }
        } catch (RuntimeException e) {
            log.warn("Vistierie agent bootstrap failed ({}); will retry on next start", e.toString());
        }
    }
}
