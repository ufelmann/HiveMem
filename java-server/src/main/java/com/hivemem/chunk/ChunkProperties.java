package com.hivemem.chunk;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "hivemem.chunk")
public class ChunkProperties {
    /** Greedy packing target for a chunk, in characters. See design §3.3. */
    private int targetChars = 2000;
    /** Hard ceiling for a single chunk; a piece above this is split further. See design §3.3. */
    private int maxChars = 3000;
    /** Lower bound for the sweep to consider a cell at all (used by the sweep, not the chunker). */
    private int minCellChars = 2000;
    /** Sweep batch size. Used starting with the sweep (Task 2). */
    private int batchSize = 50;
    /** Throttle applied to a cell whose chunking/embedding failed. Used starting with the sweep. */
    private Duration backoff = Duration.ofMinutes(15);
    /** Feature switch for the sweep. Used starting with the sweep. */
    private boolean enabled = true;
    /** Sweep tick interval. Used starting with the sweep (Task 2, fix round 2) — modelled as a
     *  Duration alongside backoff, referenced via SpEL from CellChunkSweep the way
     *  ConsumptionRecoverySweep references ConsumptionProperties.recoveryInterval, instead of a
     *  standalone property invented at the {@code @Scheduled} call site. */
    private Duration sweepInterval = Duration.ofMinutes(1);

    public int getTargetChars() { return targetChars; }
    public void setTargetChars(int v) { this.targetChars = v; }
    public int getMaxChars() { return maxChars; }
    public void setMaxChars(int v) { this.maxChars = v; }
    public int getMinCellChars() { return minCellChars; }
    public void setMinCellChars(int v) { this.minCellChars = v; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int v) { this.batchSize = v; }
    public Duration getBackoff() { return backoff; }
    public void setBackoff(Duration v) { this.backoff = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public Duration getSweepInterval() { return sweepInterval; }
    public void setSweepInterval(Duration v) { this.sweepInterval = v; }
}
