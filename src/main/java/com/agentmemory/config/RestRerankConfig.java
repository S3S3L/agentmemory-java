package com.agentmemory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

@Data
@Configuration
@ConfigurationProperties(prefix = "rest.rerank")
public class RestRerankConfig {
    private String endpoint;
}
