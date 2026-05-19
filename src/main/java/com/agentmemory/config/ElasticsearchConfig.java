package com.agentmemory.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return new ElasticsearchClient(
            new RestClientTransport(restClient, new JacksonJsonpMapper(mapper))
        );
    }

    @Bean
    public ApplicationRunner indexInitializer(ElasticsearchClient client) {
        return (ApplicationArguments args) -> {
            initIndex(client, "memory-observations");
            initIndex(client, "memory-consolidated");
            initIndex(client, "memory-sessions");
            addMissingFields(client);
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

    /**
     * Apply current mapping to existing indices to add new fields.
     * ES allows adding new fields to existing indices via _mapping API.
     */
    private void addMissingFields(ElasticsearchClient client) throws IOException {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var mappings = new ClassPathResource("es-index-mappings.json").getInputStream();
        com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(mappings);
        com.fasterxml.jackson.databind.JsonNode props = root.path("mappings").path("properties");
        if (props.isMissingNode()) return;

        // Build full properties JSON for putMapping
        String propsJson = mapper.writeValueAsString(
            java.util.Map.of("properties", mapper.treeToValue(props, java.util.Map.class)));
        try {
            client.indices().putMapping(m -> m
                .index("memory-observations")
                .withJson(new java.io.ByteArrayInputStream(propsJson.getBytes()))
            );
            log.info("Applied updated mapping to memory-observations");
        } catch (Exception e) {
            log.warn("Failed to apply mapping update: {}", e.getMessage());
        }
    }
}
