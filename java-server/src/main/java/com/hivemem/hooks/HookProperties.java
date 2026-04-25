package com.hivemem.hooks;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "hivemem.hooks")
public class HookProperties {

    private boolean enabled = true;
    private double relevanceThreshold = 0.65;
    private int maxCells = 3;
    private int dedupWindowTurns = 5;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public double getRelevanceThreshold() { return relevanceThreshold; }
    public void setRelevanceThreshold(double v) { this.relevanceThreshold = v; }

    public int getMaxCells() { return maxCells; }
    public void setMaxCells(int v) { this.maxCells = v; }

    public int getDedupWindowTurns() { return dedupWindowTurns; }
    public void setDedupWindowTurns(int v) { this.dedupWindowTurns = v; }
}
