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
    private final boolean realEmbeddingAvailable;

    public DashScopeService(DashScopeConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
        this.mapper = new ObjectMapper();
        this.realEmbeddingAvailable = config.getApiKey() != null && !config.getApiKey().isBlank();
    }

    /**
     * Returns true if DashScope API key is configured and embedding calls
     * will use the real API (not fallback).
     */
    public boolean isRealEmbeddingAvailable() {
        return realEmbeddingAvailable;
    }

    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return fallbackEmbed(text != null ? text : "");
        }
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            return fallbackEmbed(text);
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
                    return fallbackEmbed(text);
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
            log.warn("DashScope embedding error, using fallback: {}", e.getMessage());
            return fallbackEmbed(text);
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

    /**
     * Generate a deterministic non-zero embedding from text hash.
     * Used when DashScope API is unavailable. Produces a unit vector
     * so cosine similarity is valid.
     */
    private float[] fallbackEmbed(String text) {
        int dim = config.getEmbeddingDimensions();
        float[] vec = new float[dim];
        int hash = text.hashCode();
        // Fill with deterministic pseudo-random values from hash
        long seed = hash;
        for (int i = 0; i < dim; i++) {
            seed = (seed * 6364136223846793005L + 1) & Long.MAX_VALUE;
            vec[i] = ((seed & 0xFFFF) / 32768.0f) - 1.0f;
        }
        // Normalize to unit vector so cosine similarity works
        float norm = 0;
        for (float v : vec) norm += v * v;
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < dim; i++) vec[i] /= norm;
        }
        return vec;
    }
}
