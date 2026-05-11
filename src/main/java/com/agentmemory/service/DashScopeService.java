package com.agentmemory.service;

import com.agentmemory.config.DashScopeConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class DashScopeService {

    private static final Logger log = LoggerFactory.getLogger(DashScopeService.class);
    private static final String EMBEDDING_URL = "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";
    private static final String RERANK_URL = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    private final DashScopeConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper;

    public DashScopeService(DashScopeConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
        this.mapper = new ObjectMapper();
    }

    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return new float[config.getEmbeddingDimensions()];
        }

        try {
            String json = mapper.writeValueAsString(mapper.createObjectNode()
                .put("model", config.getEmbeddingModel())
                .put("input", mapper.valueToTree(List.of(text))));

            Request request = new Request.Builder()
                .url(EMBEDDING_URL)
                .header("Authorization", "Bearer " + config.getApiKey())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("DashScope embedding failed: {} {}", response.code(), response.body() != null ? response.body().string() : "");
                    return new float[config.getEmbeddingDimensions()];
                }
                JsonNode root = mapper.readTree(response.body().string());
                JsonNode embedding = root.path("output").path("embeddings").get(0).path("embedding");
                float[] vec = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vec[i] = (float) embedding.get(i).asDouble();
                }
                return vec;
            }
        } catch (Exception e) {
            log.error("DashScope embedding error", e);
            return new float[config.getEmbeddingDimensions()];
        }
    }

    public List<RerankResult> rerank(String query, List<String> documents) {
        if (documents.isEmpty() || config.getApiKey().isBlank()) {
            return List.of();
        }

        try {
            var payload = mapper.createObjectNode()
                .put("model", config.getRerankModel())
                .put("query", query)
                .put("documents", mapper.valueToTree(documents));

            Request request = new Request.Builder()
                .url(RERANK_URL)
                .header("Authorization", "Bearer " + config.getApiKey())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(mapper.writeValueAsString(payload), MediaType.parse("application/json")))
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("DashScope rerank failed: {}", response.code());
                    return List.of();
                }
                JsonNode root = mapper.readTree(response.body().string());
                var results = new java.util.ArrayList<RerankResult>();
                for (JsonNode r : root.path("output").path("results")) {
                    results.add(new RerankResult(
                        r.path("index").asInt(),
                        r.path("relevance_score").asDouble()
                    ));
                }
                return results;
            }
        } catch (Exception e) {
            log.error("DashScope rerank error", e);
            return List.of();
        }
    }

    public record RerankResult(int index, double score) {}
}
