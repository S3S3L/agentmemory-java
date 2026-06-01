package com.agentmemory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "memory")
public class MemoryProperties {
    private int tokenBudget = 2000;
    private int dedupWindowMinutes = 5;
    private int rrfK = 60;
    private int maxResultsPerSession = 8;
    private int topKBm25 = 40;
    private int topKVector = 40;
    private int topKFinal = 10;
    /** Minimum rerank score to include a result. Rerank scores are negative; closer to 0 = more relevant. */
    private double minRerankScore = -5.0;
    /**
     * Maximum characters to pass to the embedding model.
     * Uses head+tail truncation to preserve both start and end context.
     * Default targets larger-context embedding models like nomic-embed-text (8192 tokens → ~24000 chars).
     * Lower to around 3000 for short-context models like mxbai-embed-large (512 tokens).
     */
    private int maxEmbedChars = 24000;
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
    public double getMinRerankScore() { return minRerankScore; }
    public void setMinRerankScore(double minRerankScore) { this.minRerankScore = minRerankScore; }
    public int getMaxEmbedChars() { return maxEmbedChars; }
    public void setMaxEmbedChars(int maxEmbedChars) { this.maxEmbedChars = maxEmbedChars; }
    public Consolidation getConsolidation() { return consolidation; }
    public void setConsolidation(Consolidation consolidation) { this.consolidation = consolidation; }
    public Decay getDecay() { return decay; }
    public void setDecay(Decay decay) { this.decay = decay; }

    public static class Consolidation {
        private boolean enabled = true;
        private String cron = "0 0 */6 * * *";
        private long intervalMinutes = 60;
        /** Ollama model used for generating summary/fact text during consolidation. */
        private String summaryModel = "llama3.2";
        private Promotion promotion = new Promotion();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getCron() { return cron; }
        public void setCron(String cron) { this.cron = cron; }
        public long getIntervalMinutes() { return intervalMinutes; }
        public void setIntervalMinutes(long intervalMinutes) { this.intervalMinutes = intervalMinutes; }
        public String getSummaryModel() { return summaryModel; }
        public void setSummaryModel(String summaryModel) { this.summaryModel = summaryModel; }
        public Promotion getPromotion() { return promotion; }
        public void setPromotion(Promotion promotion) { this.promotion = promotion; }

        public static class Promotion {
            private int workingToEpisodicDays = 1;
            private int workingToEpisodicAccessCount = 3;
            private int episodicToSemanticDays = 7;
            private int episodicToSemanticAccessCount = 3;
            private int semanticToProceduralAccessCount = 10;

            public int getWorkingToEpisodicDays() { return workingToEpisodicDays; }
            public void setWorkingToEpisodicDays(int v) { this.workingToEpisodicDays = v; }
            public int getWorkingToEpisodicAccessCount() { return workingToEpisodicAccessCount; }
            public void setWorkingToEpisodicAccessCount(int v) { this.workingToEpisodicAccessCount = v; }
            public int getEpisodicToSemanticDays() { return episodicToSemanticDays; }
            public void setEpisodicToSemanticDays(int v) { this.episodicToSemanticDays = v; }
            public int getEpisodicToSemanticAccessCount() { return episodicToSemanticAccessCount; }
            public void setEpisodicToSemanticAccessCount(int v) { this.episodicToSemanticAccessCount = v; }
            public int getSemanticToProceduralAccessCount() { return semanticToProceduralAccessCount; }
            public void setSemanticToProceduralAccessCount(int v) { this.semanticToProceduralAccessCount = v; }
        }
    }

    public static class Decay {
        private boolean enabled = true;
        private String cron = "0 0 2 * * *";
        private long intervalMinutes = 360;
        private boolean softDelete = true;
        private int staleEpisodicDays = 30;
        private int staleSemanticDays = 90;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getCron() { return cron; }
        public void setCron(String cron) { this.cron = cron; }
        public long getIntervalMinutes() { return intervalMinutes; }
        public void setIntervalMinutes(long intervalMinutes) { this.intervalMinutes = intervalMinutes; }
        public boolean isSoftDelete() { return softDelete; }
        public void setSoftDelete(boolean softDelete) { this.softDelete = softDelete; }
        public int getStaleEpisodicDays() { return staleEpisodicDays; }
        public void setStaleEpisodicDays(int staleEpisodicDays) { this.staleEpisodicDays = staleEpisodicDays; }
        public int getStaleSemanticDays() { return staleSemanticDays; }
        public void setStaleSemanticDays(int staleSemanticDays) { this.staleSemanticDays = staleSemanticDays; }
    }
}
