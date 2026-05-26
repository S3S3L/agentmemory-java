package com.agentmemory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

@Data
@Configuration
@ConfigurationProperties(prefix = "ollama")
public class OllamaConfig {
    private String endpoint;
    private String embeddingModel;
    private int dimensions;
}
