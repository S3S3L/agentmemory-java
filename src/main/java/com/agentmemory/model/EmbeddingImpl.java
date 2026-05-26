package com.agentmemory.model;

public enum EmbeddingImpl {
    DASH_SCOPE,
    OLLAMA;

    public static EmbeddingImpl fromString(String value) {
        for (EmbeddingImpl impl : EmbeddingImpl.values()) {
            if (impl.name().equalsIgnoreCase(value)) {
                return impl;
            }
        }
        throw new IllegalArgumentException("Unknown embedding implementation: " + value);
    }
}
