package com.agentmemory.service;

import com.agentmemory.config.DashScopeConfig;
import com.agentmemory.config.OllamaConfig;
import com.agentmemory.config.RestRerankConfig;
import com.agentmemory.service.embed.DashScopeEmbeddingService;
import com.agentmemory.service.embed.EmbeddingService;
import com.agentmemory.service.embed.OllamaEmbeddingService;
import com.agentmemory.service.rerank.DashScopeRerankService;
import com.agentmemory.service.rerank.RerankService;
import com.agentmemory.service.rerank.RestRerankService;

class TestServiceFactory {

    static EmbeddingService createEmbeddingService() {
        String impl = env("AGENTMEMORY_EMBEDDING", "OLLAMA").toUpperCase();
        switch (impl) {
            case "DASH_SCOPE": {
                var config = new DashScopeConfig();
                config.setApiKey(env("DASHSCOPE_API_KEY", ""));
                config.setEmbeddingModel(env("DASHSCOPE_EMBEDDING_MODEL", "text-embedding-v4"));
                config.setEmbeddingDimensions(Integer.parseInt(env("DASHSCOPE_EMBEDDING_DIMENSIONS", "1024")));
                return new DashScopeEmbeddingService(config);
            }
            case "OLLAMA": {
                var config = new OllamaConfig();
                config.setEndpoint(env("OLLAMA_ENDPOINT", "http://localhost:11434"));
                config.setEmbeddingModel(env("OLLAMA_EMBEDDING_MODEL", "nomic-embed-text"));
                config.setDimensions(Integer.parseInt(env("OLLAMA_DIMENSIONS", "1024")));
                return new OllamaEmbeddingService(config);
            }
            default:
                throw new IllegalArgumentException("Unsupported AGENTMEMORY_EMBEDDING: " + impl);
        }
    }

    static RerankService createRerankService() {
        String impl = env("AGENTMEMORY_RERANK", "REST").toUpperCase();
        switch (impl) {
            case "DASH_SCOPE": {
                var config = new DashScopeConfig();
                config.setApiKey(env("DASHSCOPE_API_KEY", ""));
                config.setRerankModel(env("DASHSCOPE_RERANK_MODEL", "gte-rerank"));
                return new DashScopeRerankService(config);
            }
            case "REST": {
                var config = new RestRerankConfig();
                config.setEndpoint(env("REST_RERANK_ENDPOINT", "http://localhost:8000/rerank"));
                return new RestRerankService(config);
            }
            default:
                throw new IllegalArgumentException("Unsupported AGENTMEMORY_RERANK: " + impl);
        }
    }

    private static String env(String name, String defaultValue) {
        String val = System.getenv(name);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }
}
