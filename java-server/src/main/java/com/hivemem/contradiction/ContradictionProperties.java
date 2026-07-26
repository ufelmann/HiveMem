package com.hivemem.contradiction;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Throttle knobs for the contradiction sweep. The binding constraint is not cost —
 * Vistierie runs on a subscription — but the rolling rate-limit window, which is shared
 * with interactive development work. Defaults are deliberately conservative.
 */
@Component
@ConfigurationProperties(prefix = "hivemem.contradiction")
public class ContradictionProperties {
    /** Feature gate for the whole contradiction sweep. */
    private boolean enabled = false;
    /** How often the sweep ticks; background debt, not a live path. */
    private Duration sweepInterval = Duration.ofHours(1);
    /** How often stale dispatched jobs are recovered. */
    private Duration reconcileInterval = Duration.ofMinutes(5);
    /** Candidate pairs per Stage-B run. */
    private int batchSize = 25;
    /** Hard ceiling on dispatched runs per UTC day, shared by both stages. */
    private int maxRunsPerDay = 4;
    /** A dispatched job with no callback for this long is considered crashed. */
    private Duration staleThreshold = Duration.ofMinutes(10);
    /** Dispatches a single item may receive before it is parked as deferred. */
    private int maxAttempts = 3;
    /** Cap on pairs contributed by one (subject, predicate) group per batch, so one large group cannot monopolise the quota. */
    private int maxPairsPerGroup = 3;
    /** Predicates per Stage-A run (distinct from batchSize, which counts pairs). */
    private int cardinalityBatchSize = 10;
    /** Sample objects sent per predicate in the Stage-A payload. */
    private int cardinalitySamples = 3;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getSweepInterval() { return sweepInterval; }
    public void setSweepInterval(Duration v) { this.sweepInterval = v; }
    public Duration getReconcileInterval() { return reconcileInterval; }
    public void setReconcileInterval(Duration v) { this.reconcileInterval = v; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int v) { this.batchSize = v; }
    public int getMaxRunsPerDay() { return maxRunsPerDay; }
    public void setMaxRunsPerDay(int v) { this.maxRunsPerDay = v; }
    public Duration getStaleThreshold() { return staleThreshold; }
    public void setStaleThreshold(Duration v) { this.staleThreshold = v; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int v) { this.maxAttempts = v; }
    public int getMaxPairsPerGroup() { return maxPairsPerGroup; }
    public void setMaxPairsPerGroup(int v) { this.maxPairsPerGroup = v; }
    public int getCardinalityBatchSize() { return cardinalityBatchSize; }
    public void setCardinalityBatchSize(int v) { this.cardinalityBatchSize = v; }
    public int getCardinalitySamples() { return cardinalitySamples; }
    public void setCardinalitySamples(int v) { this.cardinalitySamples = v; }
}
