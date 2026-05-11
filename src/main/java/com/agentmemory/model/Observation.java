package com.agentmemory.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record Observation(
    String id,
    String content,
    float[] embedding,
    MemoryTier tier,
    String sessionId,
    String toolName,
    String input,
    String output,
    String filePath,
    Instant timestamp,
    Map<String, Object> metadata,
    List<String> tags,
    boolean isActive,
    int accessCount,
    Instant lastAccessed
) {
    public static ObservationBuilder builder() {
        return new ObservationBuilder();
    }

    public static class ObservationBuilder {
        private String id;
        private String content;
        private float[] embedding;
        private MemoryTier tier = MemoryTier.WORKING;
        private String sessionId;
        private String toolName;
        private String input;
        private String output;
        private String filePath;
        private Instant timestamp;
        private Map<String, Object> metadata;
        private List<String> tags;
        private boolean isActive = true;
        private int accessCount = 0;
        private Instant lastAccessed;

        public ObservationBuilder id(String id) { this.id = id; return this; }
        public ObservationBuilder content(String content) { this.content = content; return this; }
        public ObservationBuilder embedding(float[] embedding) { this.embedding = embedding; return this; }
        public ObservationBuilder tier(MemoryTier tier) { this.tier = tier; return this; }
        public ObservationBuilder sessionId(String sessionId) { this.sessionId = sessionId; return this; }
        public ObservationBuilder toolName(String toolName) { this.toolName = toolName; return this; }
        public ObservationBuilder input(String input) { this.input = input; return this; }
        public ObservationBuilder output(String output) { this.output = output; return this; }
        public ObservationBuilder filePath(String filePath) { this.filePath = filePath; return this; }
        public ObservationBuilder timestamp(Instant timestamp) { this.timestamp = timestamp; return this; }
        public ObservationBuilder metadata(Map<String, Object> metadata) { this.metadata = metadata; return this; }
        public ObservationBuilder tags(List<String> tags) { this.tags = tags; return this; }
        public ObservationBuilder isActive(boolean isActive) { this.isActive = isActive; return this; }
        public ObservationBuilder accessCount(int accessCount) { this.accessCount = accessCount; return this; }
        public ObservationBuilder lastAccessed(Instant lastAccessed) { this.lastAccessed = lastAccessed; return this; }

        public Observation build() {
            return new Observation(id, content, embedding, tier, sessionId, toolName, input, output, filePath, timestamp, metadata, tags, isActive, accessCount, lastAccessed);
        }
    }
}
