package com.hivemem.hooks;

import com.hivemem.search.SearchWeights;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "hivemem.hooks")
public class HookProperties {

    private boolean enabled = true;
    private double relevanceThreshold = 0.65;
    private double minSemanticScore = 0.35;
    private int maxCells = 3;
    private int dedupWindowTurns = 5;
    private Weights weights = new Weights();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public double getRelevanceThreshold() { return relevanceThreshold; }
    public void setRelevanceThreshold(double v) { this.relevanceThreshold = v; }

    public double getMinSemanticScore() { return minSemanticScore; }
    public void setMinSemanticScore(double v) { this.minSemanticScore = v; }

    public int getMaxCells() { return maxCells; }
    public void setMaxCells(int v) { this.maxCells = v; }

    public int getDedupWindowTurns() { return dedupWindowTurns; }
    public void setDedupWindowTurns(int v) { this.dedupWindowTurns = v; }

    public Weights getWeights() { return weights; }
    public void setWeights(Weights weights) { this.weights = weights; }

    public static class Weights {
        private double semantic = 0.70;
        private double keyword = 0.10;
        private double recency = 0.05;
        private double importance = 0.05;
        private double popularity = 0.05;
        private double graphProximity = 0.05;

        public SearchWeights toSearchWeights() {
            return new SearchWeights(semantic, keyword, recency, importance, popularity, graphProximity);
        }

        public double getSemantic() { return semantic; }
        public void setSemantic(double v) { this.semantic = v; }

        public double getKeyword() { return keyword; }
        public void setKeyword(double v) { this.keyword = v; }

        public double getRecency() { return recency; }
        public void setRecency(double v) { this.recency = v; }

        public double getImportance() { return importance; }
        public void setImportance(double v) { this.importance = v; }

        public double getPopularity() { return popularity; }
        public void setPopularity(double v) { this.popularity = v; }

        public double getGraphProximity() { return graphProximity; }
        public void setGraphProximity(double v) { this.graphProximity = v; }
    }
}
