package com.agentmemory.service;

import java.util.List;

/**
 * Common interface for rerank providers.
 */
public interface RerankService {

    /**
     * Rerank a list of documents relative to the given query.
     * Returns a list of results with original index and relevance score,
     * sorted by score descending. Returns empty list when the provider is unavailable.
     */
    List<RerankResult> rerank(String query, List<String> documents);

    record RerankResult(int index, double score) {}
}
