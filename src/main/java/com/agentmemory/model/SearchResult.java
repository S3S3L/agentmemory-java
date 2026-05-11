package com.agentmemory.model;

public record SearchResult(
    String id,
    String content,
    MemoryTier tier,
    String sessionId,
    String toolName,
    String filePath,
    double score,
    double bm25Score,
    double vectorScore,
    double rerankScore
) {}
