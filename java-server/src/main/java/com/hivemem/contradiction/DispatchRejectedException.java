package com.hivemem.contradiction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientResponseException;

/**
 * Thrown when Vistierie's response to a run-creation request means the run was never created, so
 * the caller must compensate the reserved job (release it back to {@code awaiting}) rather than
 * treat it as a failed attempt.
 *
 * <p>Exactly three statuses are classified as stop signals by {@link
 * #throwIfStopSignal(RestClientResponseException)}, verified against Vistierie's actual source
 * (RunController#trigger / AgentDispatcher#trigger), not assumed:
 *
 * <ul>
 *   <li>{@code 403 Forbidden} — the tenant's Vistierie subscription quota is exhausted. The
 *       response body distinguishes {@code budget_exceeded_monthly} from {@code
 *       budget_missing_tenant} / {@code budget_missing_agent}; that body excerpt is carried into
 *       the exception message and logged so a persistent 403 doesn't fail silently.
 *   <li>{@code 409 Conflict} — the target agent is paused.
 *   <li>{@code 404 Not Found} — the POST never reached {@code RunController#trigger} at all: wrong
 *       base URL, a reverse-proxy default page, or Vistierie having moved/been replaced. It
 *       provably means no run was created, so compensating cannot double-run.
 * </ul>
 *
 * <p><b>Two statuses that might look like they belong here do not</b>, against Vistierie's real
 * behaviour:
 *
 * <ul>
 *   <li>An unregistered agent name currently arrives as {@code 500}, not 404 —
 *       {@code RunController} throws a bare {@code RuntimeException("agent not found")} and
 *       Vistierie has no {@code @ControllerAdvice}, so Spring's default handler maps it to 500.
 *       It therefore falls into the ambiguous "may have started" bucket below; the reconcile sweep
 *       is what actually recovers it.
 *   <li>A missing {@code model_purpose} routing rule is never a dispatch-time status at all.
 *       {@code AgentDispatcher#trigger} creates the run and queues async execution before
 *       returning, so the caller already holds a 202 and a run id; {@code NoRouteException} fires
 *       later, inside the run, and surfaces only as a completion callback with
 *       {@code status != "done"}.
 * </ul>
 *
 * <p>Every other error (timeouts, connection refused, 401, 5xx) means the run may well have
 * started on Vistierie's side; those cases must be left alone for the reconcile sweep to resolve
 * via the stale-job check, not compensated here. Compensating on an ambiguous failure would risk
 * running the same item twice; burning an attempt on a stop-signal would walk the whole backlog
 * into the terminal {@code deferred} state the first time a routing rule is missing for an
 * afternoon.
 *
 * <p>{@code 401 Unauthorized} is deliberately NOT a stop signal, even though a bad tenant token
 * also deterministically means no run was created: unlike a clean 404, a 401 can originate from
 * layers other than {@code RunController} (a gateway or reverse proxy enforcing its own auth), so
 * "the request never reached the controller" is not as certain. It stays on the safe, ambiguous
 * side and is left to the reconcile sweep — at the cost of burning attempts on a token
 * misconfiguration, which is a one-time operational fix, not a per-item failure mode.
 */
public class DispatchRejectedException extends RuntimeException {

    private static final Logger log = LoggerFactory.getLogger(DispatchRejectedException.class);
    private static final int BODY_EXCERPT_LENGTH = 500;

    private final int status;

    DispatchRejectedException(int status, String bodyExcerpt) {
        super("Vistierie rejected the run request without creating a run (status " + status + "): " + bodyExcerpt);
        this.status = status;
    }

    public int status() {
        return status;
    }

    /**
     * Classifies a Vistierie run-creation failure. Throws {@link DispatchRejectedException} for
     * the three stop-signal statuses (403, 409, 404); returns normally for every other status so
     * the caller can rethrow {@code e} unchanged onto the ambiguous-failure path.
     *
     * <p>Centralised here — rather than duplicated per client — so the status set, the log line,
     * and the diagnostic body excerpt exist exactly once; {@link VistierieContradictionClient} and
     * {@link VistierieCardinalityClient} implement the identical run-creation contract and would
     * otherwise drift out of sync.
     */
    public static void throwIfStopSignal(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 403 || status == 409 || status == 404) {
            String body = e.getResponseBodyAsString();
            String excerpt = body == null || body.isEmpty()
                    ? "(empty body)"
                    : body.substring(0, Math.min(body.length(), BODY_EXCERPT_LENGTH));
            log.warn("Vistierie rejected run creation with stop-signal status {}: {}", status, excerpt);
            throw new DispatchRejectedException(status, excerpt);
        }
    }
}
