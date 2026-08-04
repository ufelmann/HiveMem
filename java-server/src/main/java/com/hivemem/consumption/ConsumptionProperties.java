package com.hivemem.consumption;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "hivemem.consumption")
public class ConsumptionProperties {
    private boolean enabled = false;
    private String dir = "/data/consumption";
    private String realm = "documents";
    private Duration pollInterval = Duration.ofSeconds(10);
    private int stableSeconds = 5;
    private int maxPages = 200;
    private double confidenceThreshold = 0.80;   // used in M2
    private int maxDispatchRetries = 3;          // used in M2
    private int workerThreads = 2;               // bounded executor size for off-thread ingest
    private boolean reassemblyEnabled = false;
    private double reassemblyConfidenceThreshold = 0.5; // aggressive: most groups commit
    private int reassemblyRenderDpi = 150;            // downscale pages for the vision payload
    private String reassemblyPurpose = "separator";   // Vistierie routing purpose
    private int reassemblyMaxTokens = 4096;
    private Duration recoveryInterval = Duration.ofMinutes(5);
    private Duration recoveryStaleThreshold = Duration.ofMinutes(30);
    private int failedRetryLimit = 3;
    private boolean blankFilterEnabled = true;
    private double blankWhiteFraction = 0.995;
    // Pre-check threshold: a page this white skips the orientation call (a white page has no
    // orientation) and is handed to the extractor as pixel-blank. Deliberately LOOSER than the 0.995
    // post-check — a lower whiteness bar, so it fires on a strict superset of that check's pages —
    // because it only suppresses the orientation call, never a deletion. Calibrated on a measured
    // duplex sample rendered at the production DPI: the value sits in the gap between the whitest
    // content page and the least white blank backside, well clear of content. See the design doc.
    private double blankSkipWhiteFraction = 0.97;
    // Floor for the review queue's degraded-batch filter. 1, not the historical 2: real batches that
    // lost page metadata lost a single page out of many, which a floor of 2 never surfaced.
    // Safe to lower only because the blank-page pre-skip (blankSkipWhiteFraction above)
    // removed the cause of blank-page-induced degradation — a white page making the model answer
    // with prose instead of JSON — that a floor of 1 would otherwise have flooded the queue with.
    private int minDegradedPages = 1;
    // Second review-queue alert branch, independent of degraded_pages: a batch that loses most of
    // its pages to the blank-page filter with zero degraded pages is otherwise invisible. Calibrated,
    // not guessed: it sits above every blank ratio observed in ordinary duplex scanning — where a
    // lower bar such as 0.30 would flag routine duplex batches — so it only fires once the blank
    // filter itself has gone wrong. See the design doc for the measured sample.
    private double blankRatioAlert = 0.60;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public String getDir() { return dir; }
    public void setDir(String v) { this.dir = v; }
    public String getRealm() { return realm; }
    public void setRealm(String v) { this.realm = v; }
    public Duration getPollInterval() { return pollInterval; }
    public void setPollInterval(Duration v) { this.pollInterval = v; }
    public int getStableSeconds() { return stableSeconds; }
    public void setStableSeconds(int v) { this.stableSeconds = v; }
    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int v) { this.maxPages = v; }
    public double getConfidenceThreshold() { return confidenceThreshold; }
    public void setConfidenceThreshold(double v) { this.confidenceThreshold = v; }
    public int getMaxDispatchRetries() { return maxDispatchRetries; }
    public void setMaxDispatchRetries(int v) { this.maxDispatchRetries = v; }
    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int v) { this.workerThreads = v; }
    public boolean isReassemblyEnabled() { return reassemblyEnabled; }
    public void setReassemblyEnabled(boolean v) { this.reassemblyEnabled = v; }
    public double getReassemblyConfidenceThreshold() { return reassemblyConfidenceThreshold; }
    public void setReassemblyConfidenceThreshold(double v) { this.reassemblyConfidenceThreshold = v; }
    public int getReassemblyRenderDpi() { return reassemblyRenderDpi; }
    public void setReassemblyRenderDpi(int v) { this.reassemblyRenderDpi = v; }
    public String getReassemblyPurpose() { return reassemblyPurpose; }
    public void setReassemblyPurpose(String v) { this.reassemblyPurpose = v; }
    public int getReassemblyMaxTokens() { return reassemblyMaxTokens; }
    public void setReassemblyMaxTokens(int v) { this.reassemblyMaxTokens = v; }
    public Duration getRecoveryInterval() { return recoveryInterval; }
    public void setRecoveryInterval(Duration v) { this.recoveryInterval = v; }
    public Duration getRecoveryStaleThreshold() { return recoveryStaleThreshold; }
    public void setRecoveryStaleThreshold(Duration v) { this.recoveryStaleThreshold = v; }
    public int getFailedRetryLimit() { return failedRetryLimit; }
    public void setFailedRetryLimit(int v) { this.failedRetryLimit = v; }
    public boolean isBlankFilterEnabled() { return blankFilterEnabled; }
    public void setBlankFilterEnabled(boolean v) { this.blankFilterEnabled = v; }
    public double getBlankWhiteFraction() { return blankWhiteFraction; }
    public void setBlankWhiteFraction(double v) { this.blankWhiteFraction = v; }
    public double getBlankSkipWhiteFraction() { return blankSkipWhiteFraction; }
    public void setBlankSkipWhiteFraction(double v) { this.blankSkipWhiteFraction = v; }
    public int getMinDegradedPages() { return minDegradedPages; }
    public void setMinDegradedPages(int v) { this.minDegradedPages = v; }
    public double getBlankRatioAlert() { return blankRatioAlert; }
    public void setBlankRatioAlert(double v) { this.blankRatioAlert = v; }
}
