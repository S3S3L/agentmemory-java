package com.agentmemory.model;

public enum RerankImpl {
    DASH_SCOPE,
    REST;

    public static RerankImpl fromString(String value) {
        for (RerankImpl impl : RerankImpl.values()) {
            if (impl.name().equalsIgnoreCase(value)) {
                return impl;
            }
        }
        throw new IllegalArgumentException("Unknown rerank implementation: " + value);
    }
}
