package com.hivemem.contradiction;

import com.hivemem.write.WriteToolService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The last piece of contradiction-detection behaviour before its HTTP surface (webhook endpoints,
 * Task 13) and MCP tools (Task 14): recording Vistierie's judge verdicts, and carrying out the
 * human decisions those verdicts eventually lead to.
 *
 * <p><b>Webhook-facing methods never throw on DOMAIN conditions</b> — an unknown run id, a
 * duplicate delivery, a non-done run, an unknown/already-moved-on pair or predicate. {@link
 * #applyPairVerdicts} and {@link #applyCardinalityVerdicts} are what a Vistierie completion
 * callback calls, and every one of those conditions is expected traffic that Task 13's controller
 * must answer 200 to, or Vistierie will keep retrying a delivery that HiveMem already understood
 * (or correctly decided to ignore). Every domain early-return below logs and returns rather than
 * raising.
 *
 * <p>Infrastructure exceptions — a {@code DataAccessException} from any repository call — are
 * deliberately NOT caught here and propagate to the caller. This is safe, not an oversight: {@link
 * ContradictionJobRepository#claim} has already flipped the job to {@code processing} before any
 * repository call that could realistically fail this way, so a redelivery after the caller's own
 * retry is just another duplicate-delivery no-op, and a job stranded {@code processing} by a crash
 * here is exactly what {@link ContradictionReconcileSweep} exists to recover. Task 13 must not add
 * its own catch for this — the promise this class makes is "never throws for a reason a webhook
 * should distinguish," not "never throws," and blurring that would hide a real infrastructure
 * failure Task 13's own error handling ought to see.
 *
 * <p><b>A run finishing with anything other {@code status: "done"} is deliberately not
 * classified further, yet.</b> A review of Vistierie's own source (during Task 9) found that a
 * missing {@code model_purpose} routing rule does <em>not</em> surface as a dispatch-time error:
 * Vistierie creates the run and returns 202 regardless, and the routing failure only shows up
 * later, as a completion callback whose {@code status} is not {@code "done"}. Blanket-burning an
 * attempt for that case would be wrong — a misconfigured route is a property of the deployment,
 * not of the items in the batch — but nothing in this codebase has yet seen what such a callback
 * actually looks like, so it cannot yet be told apart from a genuine per-item judging failure.
 * Rather than guess at a classification and bake a wrong assumption into the attempt-rule logic,
 * every non-done callback logs the raw {@code runStatus} (and the verdict count, which is {@code
 * null} when Vistierie never produced output at all) at WARN, verbatim, so Task 16's first real
 * run against Vistierie has the evidence needed to classify it correctly.
 *
 * <p>{@code runStatus} is deliberately the whole error surface today: {@link PairVerdicts} and
 * {@link CardinalityVerdicts} carry no error-text field, because nothing has yet seen a real
 * non-done callback to know what shape such a field would even have. This is a scoping decision,
 * not an oversight — if Task 16's first real run finds Vistierie reports something richer (an
 * error message, a failure code), extending the DTOs to carry it is that task's job, informed by
 * real evidence instead of a guess made here.
 */
@Service
@ConditionalOnProperty(name = "hivemem.contradiction.enabled", havingValue = "true")
public class ContradictionService {

    private static final Logger log = LoggerFactory.getLogger(ContradictionService.class);

    /**
     * Statuses a human resolution may act on. Deliberately an allow-list, not a deny-list of known
     * terminal statuses: {@code in_flight} and {@code retryable} are just as illegal a target as
     * {@code resolved}/{@code dismissed}/{@code superseded}/{@code not_contradictory} (a human must
     * not resolve a pair the judge is still working, or jump the re-dispatch queue), and a
     * deny-list would silently admit any future status added to the V0052 CHECK constraint. The
     * repository writes below ({@link ContradictionRepository#dismissBothLegitimate}, {@link
     * ContradictionRepository#requeue}, {@link ContradictionRepository#markResolved}) repeat this
     * same allow-list in their own {@code WHERE status IN (...)} guards — that is the actual
     * concurrency safety net; this set is the fast, friendly rejection for the common case where
     * nothing raced.
     */
    private static final Set<String> ACTIONABLE_PAIR_STATUSES = Set.of("pending", "deferred");

    private static final int DEFAULT_LIST_LIMIT = 50;
    private static final int MIN_LIST_LIMIT = 1;
    private static final int MAX_LIST_LIMIT = 500;

    private final ContradictionJobRepository jobs;
    private final ContradictionRepository pairs;
    private final PredicateCardinalityRepository cardinality;
    private final ContradictionProperties props;
    private final WriteToolService writeToolService;

    public ContradictionService(
            ContradictionJobRepository jobs,
            ContradictionRepository pairs,
            PredicateCardinalityRepository cardinality,
            ContradictionProperties props,
            WriteToolService writeToolService) {
        this.jobs = jobs;
        this.pairs = pairs;
        this.cardinality = cardinality;
        this.props = props;
        this.writeToolService = writeToolService;
    }

    /**
     * Apply the pair-judge's verdicts for one completed (or failed) run.
     *
     * <p>Not wrapped in {@code @Transactional}: like {@link ContradictionRepository#reserve}, a
     * crash mid-method is recovered the same way any other crash in this pipeline is — {@link
     * ContradictionJobRepository#claim} has already made this job {@code processing}, so a crash
     * here leaves it exactly where {@link ContradictionReconcileSweep} expects a stale job to be,
     * and reconciliation resolves whatever verdicts did or did not get applied before the crash.
     */
    public void applyPairVerdicts(String runId, String runStatus, List<PairVerdicts.Verdict> verdicts) {
        Optional<ContradictionJobRepository.Job> jobOpt = jobs.findByRunId(runId);
        if (jobOpt.isEmpty()) {
            log.warn("Contradiction pair callback for unknown run_id {}", runId);
            return;
        }
        ContradictionJobRepository.Job job = jobOpt.get();
        if (!jobs.claim(job.id())) {
            log.info("Duplicate delivery for contradiction pair job {} (run {}); ignoring", job.id(), runId);
            return;
        }

        if (!"done".equals(runStatus) || verdicts == null) {
            log.warn("Contradiction pair job {} (run {}) finished with status='{}', verdict count={}; "
                    + "applying attempt rule and failing the job (classification deferred, see class Javadoc)",
                    job.id(), runId, runStatus, verdicts == null ? "null" : verdicts.size());
            pairs.applyAttemptRule(job.id(), props.getMaxAttempts());
            jobs.markFailed(job.id());
            return;
        }

        for (PairVerdicts.Verdict v : verdicts) {
            if (v == null) {
                // A malformed "verdicts": [null] deserializes to a list containing a null element
                // rather than failing to parse. Skipping it (instead of letting v.pair_id() NPE) is
                // what keeps this method's never-throws-on-domain-conditions promise: an NPE here
                // would escape past the already-successful claim(), so a caller-side retry would be
                // swallowed as a duplicate delivery and the job would wait for the stale-job sweep
                // instead of being handled now.
                log.warn("Contradiction pair job {} (run {}) received a null verdict element; skipping it",
                        job.id(), runId);
                continue;
            }
            boolean applied = pairs.recordVerdict(v.pair_id(), v.contradiction(), v.confidence(), v.rationale());
            if (!applied) {
                log.info("Verdict for pair {} not applied (not in_flight: duplicate delivery or unknown pair)",
                        v.pair_id());
            }
        }

        // Rows the judge never answered must not strand: apply the attempt rule to the job's
        // remaining in_flight rows BEFORE marking the job done. A done job is not stale, so
        // ContradictionReconcileSweep never revisits it, findUnjudged excludes it (a row already
        // exists), and reReserve only picks up 'retryable' rows - without this step those rows
        // would sit in_flight forever, invisible to every later sweep.
        pairs.applyAttemptRule(job.id(), props.getMaxAttempts());
        if (!jobs.markDone(job.id())) {
            log.info("Contradiction pair job {} (run {}) was already moved to a terminal state by "
                    + "another writer (e.g. the reconcile sweep) before this callback's markDone",
                    job.id(), runId);
        }
    }

    /**
     * Apply the cardinality-judge's verdicts for one completed (or failed) run. Same shape as
     * {@link #applyPairVerdicts}; see its Javadoc for the non-done and transaction-boundary
     * rationale, both identical here.
     */
    public void applyCardinalityVerdicts(String runId, String runStatus, List<CardinalityVerdicts.Verdict> verdicts) {
        Optional<ContradictionJobRepository.Job> jobOpt = jobs.findByRunId(runId);
        if (jobOpt.isEmpty()) {
            log.warn("Contradiction cardinality callback for unknown run_id {}", runId);
            return;
        }
        ContradictionJobRepository.Job job = jobOpt.get();
        if (!jobs.claim(job.id())) {
            log.info("Duplicate delivery for contradiction cardinality job {} (run {}); ignoring", job.id(), runId);
            return;
        }

        if (!"done".equals(runStatus) || verdicts == null) {
            log.warn("Contradiction cardinality job {} (run {}) finished with status='{}', verdict count={}; "
                    + "applying attempt rule and failing the job (classification deferred, see class Javadoc)",
                    job.id(), runId, runStatus, verdicts == null ? "null" : verdicts.size());
            cardinality.applyAttemptRule(job.id(), props.getMaxAttempts());
            jobs.markFailed(job.id());
            return;
        }

        List<String> dispatched = cardinality.predicatesOfJob(job.id());
        for (CardinalityVerdicts.Verdict v : verdicts) {
            if (v == null) {
                // See applyPairVerdicts' identical guard for why this is skipped rather than left to
                // NPE past the already-successful claim().
                log.warn("Contradiction cardinality job {} (run {}) received a null verdict element; skipping it",
                        job.id(), runId);
                continue;
            }
            if (!dispatched.contains(v.predicate())) {
                log.info("Cardinality verdict for predicate '{}' ignored: not dispatched by job {}",
                        v.predicate(), job.id());
                continue;
            }
            boolean applied = cardinality.recordVerdict(v.predicate(), v.cardinality(), v.confidence(), v.rationale());
            if (!applied) {
                log.info("Cardinality verdict for '{}' not applied (already decided by a human)", v.predicate());
                continue;
            }
            if ("multi_valued".equals(v.cardinality())) {
                int superseded = pairs.supersedeForPredicate(v.predicate());
                if (superseded > 0) {
                    log.info("Superseded {} pair(s) for predicate '{}', now known multi-valued",
                            superseded, v.predicate());
                }
            }
        }

        cardinality.applyAttemptRule(job.id(), props.getMaxAttempts());
        if (!jobs.markDone(job.id())) {
            log.info("Contradiction cardinality job {} (run {}) was already moved to a terminal state by "
                    + "another writer (e.g. the reconcile sweep) before this callback's markDone",
                    job.id(), runId);
        }
    }

    /**
     * A human resolves one pending (or deferred) contradiction pair.
     *
     * <p>{@code reason} is not persisted: {@code fact_contradictions} has no human-reason column
     * (only {@code rationale}, which is the judge's own explanation and must survive review
     * history — see {@link ContradictionRepository#autoCloseInactive}'s Javadoc). It is logged for
     * audit purposes only.
     *
     * <p>{@code @Transactional} on the {@code fact_a}/{@code fact_b} branch: it performs two writes
     * (the op-logged invalidation of the losing fact, then the pair's own terminal write) that
     * together represent one human action, mirroring the precedent in {@link
     * ContradictionRepository#compensate}. Without it, a crash between the two would leave the
     * loser invalidated but the pair still {@code pending} — a human would see a "resolved" fact
     * pair still sitting in their review queue.
     *
     * @throws IllegalArgumentException if {@code id} does not name a known pair, or {@code keep} is
     *     not one of {@code fact_a}/{@code fact_b}/{@code both}/{@code requeue}
     * @throws IllegalStateException if the pair is not {@code pending} or {@code deferred} — either
     *     read here (the fast path) or discovered at write time because a concurrent caller changed
     *     it between this method's read and its write (the race-safe path; see {@link
     *     #ACTIONABLE_PAIR_STATUSES}). Either way, no fact is ever invalidated for a rejected call.
     */
    @Transactional
    public Map<String, Object> resolve(UUID id, String keep, String reason) {
        ContradictionRepository.Pair pair = pairs.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown contradiction pair: " + id));
        if (!ACTIONABLE_PAIR_STATUSES.contains(pair.status())) {
            throw new IllegalStateException(
                    "Contradiction pair " + id + " is '" + pair.status() + "', not pending or deferred");
        }
        log.info("Resolving contradiction pair {} as '{}'{}", id, keep,
                reason == null ? "" : " (reason: " + reason + ")");

        return switch (keep) {
            case "both" -> resolveBoth(id, pair);
            case "requeue" -> resolveRequeue(id);
            case "fact_a" -> resolveWinner(id, pair.factA(), pair.factB(), "fact_a");
            case "fact_b" -> resolveWinner(id, pair.factB(), pair.factA(), "fact_b");
            default -> throw new IllegalArgumentException("Unknown keep value: " + keep);
        };
    }

    private Map<String, Object> resolveBoth(UUID id, ContradictionRepository.Pair pair) {
        requireWon(id, pairs.dismissBothLegitimate(id));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id.toString());
        result.put("status", "dismissed");
        result.put("hint", "Both facts for predicate '" + pair.predicate() + "' may be legitimate. "
                + "If '" + pair.predicate() + "' is generally multi-valued, consider "
                + "predicate_cardinality(predicate='" + pair.predicate() + "', set='multi_valued') "
                + "to stop it from being re-dispatched — this is not done automatically.");
        return result;
    }

    private Map<String, Object> resolveRequeue(UUID id) {
        requireWon(id, pairs.requeue(id));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id.toString());
        result.put("status", "retryable");
        return result;
    }

    /**
     * Closes the pair BEFORE invalidating the loser, not after: {@link
     * ContradictionRepository#markResolved}'s own {@code WHERE status IN ('pending','deferred')}
     * guard is the atomic check-and-claim against a concurrent resolution of the same pair, and
     * {@link com.hivemem.write.WriteToolService#kgInvalidate} is irreversible in a way the pair
     * status is not. Invalidating first (the original, wrong order) could invalidate a fact for a
     * pair a concurrent caller had already resolved a moment earlier — this order guarantees the
     * graph is only ever touched once this call has proven it, not some other caller, owns the
     * resolution.
     */
    private Map<String, Object> resolveWinner(UUID id, UUID winner, UUID loser, String kept) {
        requireWon(id, pairs.markResolved(id));
        Map<String, Object> invalidation = writeToolService.kgInvalidate(loser);
        if (!Boolean.TRUE.equals(invalidation.get("invalidated"))) {
            log.info("kgInvalidate was a no-op for fact {} while resolving pair {} (already inactive)",
                    loser, id);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id.toString());
        result.put("status", "resolved");
        result.put("kept", kept);
        result.put("kept_fact_id", winner.toString());
        result.put("invalidated_fact_id", loser.toString());
        return result;
    }

    /**
     * The repository writes backing {@link #resolveBoth}/{@link #resolveRequeue}/{@link
     * #resolveWinner} are all conditional on the pair still being {@code pending}/{@code deferred}
     * at write time, exactly like {@link #ACTIONABLE_PAIR_STATUSES}'s read-time check above. A
     * {@code false} here means a concurrent caller won the race and changed the pair's status
     * between this method's read and its write; the caller must never report success (or, for
     * {@link #resolveWinner}, invalidate anything) in that case.
     */
    private void requireWon(UUID id, boolean written) {
        if (!written) {
            throw new IllegalStateException(
                    "Contradiction pair " + id + " changed status concurrently; resolution not applied");
        }
    }

    /**
     * A human sets (or overrides) a predicate's cardinality. Outranks every judge verdict from now
     * on — see {@link PredicateCardinalityRepository#setByHuman}. Setting {@code multi_valued}
     * additionally supersedes the predicate's still-open pairs, exactly as a judge verdict of
     * {@code multi_valued} does in {@link #applyCardinalityVerdicts}: a predicate now known
     * multi-valued must stop being re-dispatched regardless of who made that determination.
     */
    public Map<String, Object> setCardinality(String predicate, String value, String reason) {
        cardinality.setByHuman(predicate, value, reason);
        int superseded = "multi_valued".equals(value) ? pairs.supersedeForPredicate(predicate) : 0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("predicate", predicate);
        result.put("cardinality", value);
        result.put("decided_by", "human");
        result.put("superseded", superseded);
        return result;
    }

    /**
     * Pairs for the {@code contradictions} MCP tool, defaulting to the {@code pending} review
     * queue. See {@link ContradictionRepository#list} for why the {@code pending} view additionally
     * requires both facts to still be active.
     *
     * <p>{@code limit} is clamped to {@code [1, 500]} here rather than trusted from the caller: a
     * negative value is a Postgres syntax error at the {@code LIMIT} clause, and an unbounded one
     * would let a single MCP tool call pull the entire table. Task 14 is not required to validate
     * this itself.
     */
    public List<Map<String, Object>> list(String status, String subject, Integer limit) {
        String effectiveStatus = status == null ? "pending" : status;
        int effectiveLimit = limit == null
                ? DEFAULT_LIST_LIMIT
                : Math.max(MIN_LIST_LIMIT, Math.min(limit, MAX_LIST_LIMIT));

        List<Map<String, Object>> out = new ArrayList<>();
        for (ContradictionRepository.PairListRow r : pairs.list(effectiveStatus, subject, effectiveLimit)) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("fact_id", r.factA().toString());
            a.put("object", r.objectA());
            a.put("valid_from", r.validFromA());
            a.put("valid_until", r.validUntilA());

            Map<String, Object> b = new LinkedHashMap<>();
            b.put("fact_id", r.factB().toString());
            b.put("object", r.objectB());
            b.put("valid_from", r.validFromB());
            b.put("valid_until", r.validUntilB());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.id().toString());
            row.put("subject", r.subject());
            row.put("predicate", r.predicate());
            row.put("fact_a", a);
            row.put("fact_b", b);
            row.put("suggested_keep", r.suggestedKeep() == null ? null : r.suggestedKeep().toString());
            row.put("rationale", r.rationale());
            row.put("judge_confidence", r.judgeConfidence());
            row.put("status", r.status());
            row.put("detected_at", r.detectedAt());
            out.add(row);
        }
        return out;
    }
}
