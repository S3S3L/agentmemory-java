package com.agentmemory.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DashScopeConfig {

    @Value("${dashscope.api-key:}")
    private String apiKey;

    @Value("${dashscope.embedding.model:text-embedding-v4}")
    private String embeddingModel;

    @Value("${dashscope.embedding.dimensions:1024}")
    private int embeddingDimensions;

    @Value("${dashscope.rerank.model:gte-rerank}")
    private String rerankModel;

    public String getApiKey() { return apiKey; }
    public String getEmbeddingModel() { return embeddingModel; }
    public int getEmbeddingDimensions() { return embeddingDimensions; }
    public String getRerankModel() { return rerankModel; }
}
