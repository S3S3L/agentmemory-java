package com.agentmemory.service.embed;

/**
 * Common interface for text embedding providers.
 */
public interface EmbeddingService {

    /**
     * Generate a vector embedding for the given text.
     * Returns a deterministic fallback vector when the provider is unavailable.
     */
    float[] embed(String text);

    /**
     * The dimension of vectors produced by this service.
     */
    int dimensions();

    /**
     * Whether the real embedding provider (e.g. remote API) is available.
     * Callers may use this to decide whether to enable vector search.
     */
    default boolean isAvailable() { return true; }
}
