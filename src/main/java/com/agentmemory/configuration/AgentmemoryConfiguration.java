package com.agentmemory.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.agentmemory.config.AgentmemoryConfig;
import com.agentmemory.config.DashScopeConfig;
import com.agentmemory.config.OllamaConfig;
import com.agentmemory.config.RestRerankConfig;
import com.agentmemory.service.embed.DashScopeEmbeddingService;
import com.agentmemory.service.embed.EmbeddingService;
import com.agentmemory.service.embed.OllamaEmbeddingService;
import com.agentmemory.service.rerank.DashScopeRerankService;
import com.agentmemory.service.rerank.RerankService;
import com.agentmemory.service.rerank.RestRerankService;

@Configuration
public class AgentmemoryConfiguration {

    @Bean
    RerankService rerankService(AgentmemoryConfig config, RestRerankConfig restRerankConfig,
            DashScopeConfig dashScopeConfig) {
        switch (config.getRerank()) {
            case DASH_SCOPE:
                return new DashScopeRerankService(dashScopeConfig);
            case REST:
                return new RestRerankService(restRerankConfig);
            default:
                throw new IllegalArgumentException("Unsupported rerank type: " + config.getRerank());
        }
    }

    @Bean
    EmbeddingService embeddingService(AgentmemoryConfig config, OllamaConfig ollamaConfig,
            DashScopeConfig dashScopeConfig) {
        switch (config.getEmbedding()) {
            case OLLAMA:
                return new OllamaEmbeddingService(ollamaConfig);
            case DASH_SCOPE:
                return new DashScopeEmbeddingService(dashScopeConfig);
            default:
                throw new IllegalArgumentException("Unsupported embedding type: " + config.getEmbedding());
        }
    }
}
