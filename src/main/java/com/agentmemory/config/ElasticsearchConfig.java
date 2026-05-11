package com.agentmemory.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

@Configuration
public class ElasticsearchConfig {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchConfig.class);

    @Value("${elasticsearch.host:localhost}")
    private String host;

    @Value("${elasticsearch.port:9200}")
    private int port;

    @Value("${elasticsearch.scheme:http}")
    private String scheme;

    @Bean
    public RestClient restClient() {
        return RestClient.builder(
            new HttpHost(host, port, scheme)
        ).build();
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient restClient) {
        return new ElasticsearchClient(
            new RestClientTransport(restClient, new JacksonJsonpMapper())
        );
    }

    @Bean
    public ApplicationRunner indexInitializer(ElasticsearchClient client) {
        return (ApplicationArguments args) -> {
            initIndex(client, "memory-observations");
            initIndex(client, "memory-consolidated");
            initIndex(client, "memory-sessions");
        };
    }

    private void initIndex(ElasticsearchClient client, String indexName) throws IOException {
        boolean exists = client.indices().exists(e -> e.index(indexName)).value();
        if (!exists) {
            log.info("Creating index: {}", indexName);
            var mappings = new ClassPathResource("es-index-mappings.json").getInputStream();
            client.indices().create(c -> c
                .index(indexName)
                .withJson(mappings)
            );
            log.info("Index created: {}", indexName);
        }
    }
}
