package com.agentmemory.service.embed;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.springframework.util.StringUtils;

import com.agentmemory.config.OllamaConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

public class OllamaEmbeddingService implements EmbeddingService {
    private final OllamaConfig config;

    private final OkHttpClient httpClient;
    private final ObjectMapper mapper;

    public OllamaEmbeddingService(OllamaConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        this.mapper = new ObjectMapper();
    }

    @Override
    public float[] embed(String text) {
        var payload = mapper.createObjectNode();
        payload.put("model", config.getEmbeddingModel());
        payload.put("input", text);
        payload.put("dimensions", config.getDimensions());

        try {
            Request request = new Request.Builder()
                    .url(config.getEndpoint() + "/api/embed")
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(mapper.writeValueAsString(payload), MediaType.parse("application/json")))
                    .build();
            try (var response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new RuntimeException("Failed to get embedding from Ollama: " + response.code() + " " + response.message());
                }
                
                var responseBody = response.body() != null ? response.body().string() : "";
                if (!StringUtils.hasLength(responseBody)) {
                    throw new RuntimeException("Empty response from Ollama");
                }

                var embeddingsNode = mapper.readTree(responseBody).get("embeddings");
                if (embeddingsNode == null || !embeddingsNode.isArray() || embeddingsNode.isEmpty()) {
                    throw new RuntimeException(
                            "Invalid response format from Ollama: 'embeddings' field is missing or empty");
                }

                var embeddingNode = embeddingsNode.get(0);
                if (embeddingNode == null || !embeddingNode.isArray()) {
                    throw new RuntimeException(
                            "Invalid response format from Ollama: first embedding is missing or not an array");
                }

                return mapper.treeToValue(embeddingNode, new TypeReference<float[]>() {
                });
            } catch (IOException e) {
                throw new RuntimeException("Failed to execute embedding request", e);
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize embedding request payload", e);
        }
    }

    @Override
    public int dimensions() {
        return config.getDimensions(); 
    }

}
