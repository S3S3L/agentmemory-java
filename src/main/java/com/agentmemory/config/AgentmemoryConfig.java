package com.agentmemory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.agentmemory.model.EmbeddingImpl;
import com.agentmemory.model.RerankImpl;

import lombok.Data;

@Data
@Configuration
@ConfigurationProperties(prefix = "agentmemory")
public class AgentmemoryConfig {
    private EmbeddingImpl embedding;
    private RerankImpl rerank;
}
