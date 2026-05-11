package com.agentmemory.model;

import java.util.List;

public record MemorySearchRequest(
    String query,
    String projectId,
    String sessionId,
    String filePath,
    int topK,
    List<MemoryTier> tiers
) {
    public MemorySearchRequest(String query) {
        this(query, null, null, null, 10, null);
    }
}
