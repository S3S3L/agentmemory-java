package com.agentmemory.service.embed;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.agentmemory.config.DashScopeConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class DashScopeEmbeddingService implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(DashScopeEmbeddingService.class);
    private static final String EMBEDDING_URL = "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";

    private final DashScopeConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper;
    private final boolean realEmbeddingAvailable;

    public DashScopeEmbeddingService(DashScopeConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
        this.mapper = new ObjectMapper();
        this.realEmbeddingAvailable = config.getApiKey() != null && !config.getApiKey().isBlank();
    }

    @Override
    public boolean isAvailable() {
        return realEmbeddingAvailable;
    }

    @Override
    public int dimensions() {
        return config.getEmbeddingDimensions();
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return fallbackEmbed(text != null ? text : "");
        }
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            return fallbackEmbed(text);
        }

        try {
            var rootNode = mapper.createObjectNode();
            rootNode.put("model", config.getEmbeddingModel());
            var inputNode = mapper.createObjectNode();
            inputNode.set("texts", mapper.valueToTree(List.of(text)));
            rootNode.set("input", inputNode);
            var paramsNode = mapper.createObjectNode();
            paramsNode.put("text_type", "query");
            rootNode.set("parameters", paramsNode);
            String json = mapper.writeValueAsString(rootNode);

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

    /**
     * Generate a deterministic non-zero embedding from text hash.
     * Used when DashScope API is unavailable. Produces a unit vector
     * so cosine similarity is valid.
     */
    private float[] fallbackEmbed(String text) {
        int dim = config.getEmbeddingDimensions();
        float[] vec = new float[dim];
        int hash = text.hashCode();
        long seed = hash;
        for (int i = 0; i < dim; i++) {
            seed = (seed * 6364136223846793005L + 1) & Long.MAX_VALUE;
            vec[i] = ((seed & 0xFFFF) / 32768.0f) - 1.0f;
        }
        float norm = 0;
        for (float v : vec) norm += v * v;
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < dim; i++) vec[i] /= norm;
        }
        return vec;
    }
}
