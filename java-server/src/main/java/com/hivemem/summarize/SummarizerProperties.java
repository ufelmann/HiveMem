package com.hivemem.summarize;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "hivemem.summarize")
public class SummarizerProperties {

    private boolean enabled = false;
    private String anthropicApiKey = "";
    private String model = "claude-haiku-4-5-20251001";
    private double dailyBudgetUsd = 1.00;
    private Duration backfillInterval = Duration.ofMinutes(5);
    private int backfillBatchSize = 10;
    private int callTimeoutSeconds = 30;
    private int maxInputChars = 8000;
    private int summaryThresholdChars = 500;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public String getAnthropicApiKey() { return anthropicApiKey; }
    public void setAnthropicApiKey(String v) { this.anthropicApiKey = v; }
    public String getModel() { return model; }
    public void setModel(String v) { this.model = v; }
    public double getDailyBudgetUsd() { return dailyBudgetUsd; }
    public void setDailyBudgetUsd(double v) { this.dailyBudgetUsd = v; }
    public Duration getBackfillInterval() { return backfillInterval; }
    public void setBackfillInterval(Duration v) { this.backfillInterval = v; }
    public int getBackfillBatchSize() { return backfillBatchSize; }
    public void setBackfillBatchSize(int v) { this.backfillBatchSize = v; }
    public int getCallTimeoutSeconds() { return callTimeoutSeconds; }
    public void setCallTimeoutSeconds(int v) { this.callTimeoutSeconds = v; }
    public int getMaxInputChars() { return maxInputChars; }
    public void setMaxInputChars(int v) { this.maxInputChars = v; }
    public int getSummaryThresholdChars() { return summaryThresholdChars; }
    public void setSummaryThresholdChars(int v) { this.summaryThresholdChars = v; }
}
