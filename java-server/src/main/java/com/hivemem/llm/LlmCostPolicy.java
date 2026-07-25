package com.hivemem.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Decides what a call costs, in EUR. Vistierie's {@code cost_micros} is the cost of record;
 * the price table below is a fallback for the case where it is absent, not the normal path.
 *
 * <p>All amounts are EUR — Vistierie prices in EUR-micros and HiveMem passes that unit through
 * rather than duplicating Vistierie's baked-in USD→EUR rate. See Decision 2 of the design spec.
 */
public class LlmCostPolicy {

    private static final Logger log = LoggerFactory.getLogger(LlmCostPolicy.class);

    /** Vistierie's provider name for the Claude subscription route; those calls are free. */
    private static final String SUBSCRIPTION = "claude-subscription";

    private static final BigDecimal MILLION = new BigDecimal(1_000_000);
    private static final BigDecimal CACHE_WRITE_MULTIPLIER = new BigDecimal("1.25");
    private static final BigDecimal CACHE_READ_MULTIPLIER = new BigDecimal("0.1");
    private static final int SCALE = 6;

    private record Rates(BigDecimal input, BigDecimal output) {}

    /**
     * EUR per 1M tokens. Mirrors the models Vistierie's own PriceTable serves, so a fallback
     * estimate never lands below what Vistierie would have charged.
     */
    private static final Map<String, Rates> RATES = Map.of(
            "claude-haiku-4-5",  rates("0.92", "4.60"),
            "claude-sonnet-4-6", rates("2.76", "13.80"),
            "claude-sonnet-5",   rates("2.76", "13.80"),
            "claude-opus-5",     rates("4.60", "23.00"),
            "claude-opus-4-7",   rates("13.80", "69.00"),
            "claude-opus-4-8",   rates("13.80", "69.00"));

    /**
     * {@link #normalize} matches by prefix, and {@code Map.keySet()} has no defined iteration
     * order — so once any key becomes a prefix of another (say "claude-opus-5" and a future
     * "claude-opus-5-1"), plain iteration would resolve nondeterministically. Longest first
     * makes the match the most specific one, always.
     */
    private static final List<String> KEYS_LONGEST_FIRST = RATES.keySet().stream()
            .sorted((a, b) -> a.length() != b.length() ? b.length() - a.length() : a.compareTo(b))
            .toList();

    /** Unknown model: charge the most expensive rate Vistierie serves — fail-safe upward. */
    private static final Rates UNKNOWN = rates("13.80", "69.00");

    private static Rates rates(String in, String out) {
        return new Rates(new BigDecimal(in), new BigDecimal(out));
    }

    /** The EUR amount to book for this call. */
    public BigDecimal eurFor(LlmCallCost call) {
        // Strictly positive on purpose, not != 0: a negative cost_micros would CREDIT the daily
        // budget and defeat the spend gate, so it is treated as "no cost reported" and routed
        // to the fail-safe estimate path (which warns). Do not relax this to != 0.
        if (call.costMicros() > 0) {
            // Exact by construction: no division, no rounding mode to argue about.
            return BigDecimal.valueOf(call.costMicros(), SCALE);
        }
        if (call == LlmCallCost.ZERO) {
            // Null body: no call happened, so no cost and nothing worth warning about.
            return BigDecimal.ZERO.setScale(SCALE);
        }
        if (SUBSCRIPTION.equals(call.provider())) {
            // Zero is the truth here — the subscription has no marginal cost per call.
            return BigDecimal.ZERO.setScale(SCALE);
        }
        BigDecimal estimate = estimate(call);
        log.warn("Vistierie reported no cost for a non-subscription call: provider={} model={} "
                        + "in={} cacheW={} cacheR={} out={} — booking estimate €{}",
                call.provider() == null ? "unknown" : call.provider(),
                call.model() == null ? "unknown" : call.model(),
                call.inputTokens(), call.cacheCreationTokens(), call.cacheReadTokens(),
                call.outputTokens(), estimate);
        return estimate;
    }

    private BigDecimal estimate(LlmCallCost call) {
        Rates r = RATES.getOrDefault(normalize(call.model()), UNKNOWN);
        // Sum at full precision and round ONCE — per-component rounding would book a single
        // cache-read token (~1e-7 EUR) as zero.
        BigDecimal total = perMillion(call.inputTokens(), r.input())
                .add(perMillion(call.cacheCreationTokens(), r.input().multiply(CACHE_WRITE_MULTIPLIER)))
                .add(perMillion(call.cacheReadTokens(), r.input().multiply(CACHE_READ_MULTIPLIER)))
                .add(perMillion(call.outputTokens(), r.output()));
        return total.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal perMillion(int tokens, BigDecimal ratePerMillion) {
        // Floor at zero: a negative usage field must never subtract from the day's spend.
        return new BigDecimal(Math.max(tokens, 0)).multiply(ratePerMillion)
                .divide(MILLION, SCALE + 6, RoundingMode.HALF_UP);
    }

    /** Bedrock inference-profile prefixes; the same model id wearing a routing region. */
    private static final String[] PROFILE_PREFIXES = {
            "eu.anthropic.", "us.anthropic.", "apac.anthropic.",
            "global.anthropic.", "anthropic."};

    /** What may legitimately remain after a known key: a release date, and nothing else. */
    private static final Pattern DATE_SUFFIX = Pattern.compile("-\\d{8}");

    /**
     * Strips Bedrock inference-profile prefixes and version suffixes, mirroring Vistierie's
     * PriceTable.normalize, so "eu.anthropic.claude-haiku-4-5-20251001-v1:0" resolves. What is
     * left may then carry a bare release date ("claude-haiku-4-5-20261001"), which still names
     * the same model.
     *
     * <p>Anything else left over is a DIFFERENT model, not a build of the matched one:
     * "claude-opus-5-1-20261101" is opus 5.1, and resolving it to the "claude-opus-5" row would
     * book its price at a third of the truth. Unrecognised ids must fall through to
     * {@link #UNKNOWN} — under-charging silently is the one failure mode this class exists to
     * avoid.
     */
    private static String normalize(String model) {
        if (model == null) return "";
        String m = model;
        for (String prefix : PROFILE_PREFIXES) {
            if (m.startsWith(prefix)) { m = m.substring(prefix.length()); break; }
        }
        m = m.replaceAll("-\\d{8}-v\\d+:\\d+$", "");
        for (String known : KEYS_LONGEST_FIRST) {
            if (!m.startsWith(known)) continue;
            String rest = m.substring(known.length());
            if (rest.isEmpty() || DATE_SUFFIX.matcher(rest).matches()) return known;
        }
        return m;
    }
}
