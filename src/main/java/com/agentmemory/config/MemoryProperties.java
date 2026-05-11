package com.agentmemory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "memory")
public class MemoryProperties {
    private int tokenBudget = 2000;
    private int dedupWindowMinutes = 5;
    private int rrfK = 60;
    private int maxResultsPerSession = 3;
    private int topKBm25 = 20;
    private int topKVector = 20;
    private int topKFinal = 10;
    private Consolidation consolidation = new Consolidation();
    private Decay decay = new Decay();

    public int getTokenBudget() { return tokenBudget; }
    public void setTokenBudget(int tokenBudget) { this.tokenBudget = tokenBudget; }
    public int getDedupWindowMinutes() { return dedupWindowMinutes; }
    public void setDedupWindowMinutes(int dedupWindowMinutes) { this.dedupWindowMinutes = dedupWindowMinutes; }
    public int getRrfK() { return rrfK; }
    public void setRrfK(int rrfK) { this.rrfK = rrfK; }
    public int getMaxResultsPerSession() { return maxResultsPerSession; }
    public void setMaxResultsPerSession(int maxResultsPerSession) { this.maxResultsPerSession = maxResultsPerSession; }
    public int getTopKBm25() { return topKBm25; }
    public void setTopKBm25(int topKBm25) { this.topKBm25 = topKBm25; }
    public int getTopKVector() { return topKVector; }
    public void setTopKVector(int topKVector) { this.topKVector = topKVector; }
    public int getTopKFinal() { return topKFinal; }
    public void setTopKFinal(int topKFinal) { this.topKFinal = topKFinal; }
    public Consolidation getConsolidation() { return consolidation; }
    public void setConsolidation(Consolidation consolidation) { this.consolidation = consolidation; }
    public Decay getDecay() { return decay; }
    public void setDecay(Decay decay) { this.decay = decay; }

    public static class Consolidation {
        private boolean enabled = true;
        private String cron = "0 0 */6 * * *";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getCron() { return cron; }
        public void setCron(String cron) { this.cron = cron; }
    }

    public static class Decay {
        private boolean enabled = true;
        private String cron = "0 0 2 * * *";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getCron() { return cron; }
        public void setCron(String cron) { this.cron = cron; }
    }
}
